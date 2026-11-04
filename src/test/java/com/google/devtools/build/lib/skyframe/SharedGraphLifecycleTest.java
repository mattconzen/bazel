// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.testutil.ManualClock;
import com.google.devtools.build.lib.vfs.DigestHashFunction;
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem;
import com.google.devtools.build.skyframe.LayeredInMemoryGraph;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Command barrier, exact generation, retry and content-verification contracts. */
@RunWith(JUnit4.class)
public final class SharedGraphLifecycleTest {
  private final InMemoryFileSystem fs =
      new InMemoryFileSystem(new ManualClock(), DigestHashFunction.SHA256);
  private final LayeredInMemoryGraph graph = new LayeredInMemoryGraph(false);
  private final SharedGraphLifecycle lifecycle = new SharedGraphLifecycle("heap");

  @After
  public void cleanup() {
    graph.cleanupInterningPools();
  }

  private Path write(String name, String content) throws Exception {
    Path file = fs.getPath(name);
    file.getParentDirectory().createDirectoryAndParents();
    FileSystemUtils.writeContentAsLatin1(file, content);
    return file;
  }

  private RootedPath track(Path path) {
    var key = RootedPath.toRootedPath(Root.absoluteRoot(fs), path.asFragment());
    graph.createIfAbsentBatch(null, Reason.OTHER, ImmutableList.of(key));
    return key;
  }

  private String prepare() throws Exception {
    lifecycle.noteCommandResult("build", true);
    lifecycle.prepare(graph, "", "");
    return (String) lifecycle.status().get("generation");
  }

  @Test
  public void fingerprintsDetectContentWithIdenticalMetadata() throws Exception {
    var file = write("/work/input", "alpha");
    var key = track(file);
    var before = file.stat(Symlinks.NOFOLLOW);
    String generation = prepare();
    FileSystemUtils.writeContentAsLatin1(file, "bravo");
    var after = file.stat(Symlinks.NOFOLLOW);
    assertThat(after.getNodeId()).isEqualTo(before.getNodeId());
    assertThat(after.getSize()).isEqualTo(before.getSize());
    assertThat(after.getLastModifiedTimeNanos()).isEqualTo(before.getLastModifiedTimeNanos());
    assertThat(after.getLastChangeTimeNanos()).isEqualTo(before.getLastChangeTimeNanos());
    assertThat(lifecycle.commandRejection("query")).contains("PREPARED");
    assertThat(lifecycle.commandRejection("build")).contains("PREPARED");
    assertThrows(IllegalStateException.class, () -> lifecycle.beginReconciliation("wrong"));
    lifecycle.beginReconciliation(generation);
    assertThat(lifecycle.changedKeys()).containsExactly(key);
    lifecycle.finishReconciliation(1);
    assertThat(lifecycle.commandRejection("query")).isNull();
    assertThat(lifecycle.status().get("changed_nodes")).isEqualTo(1);
  }

  @Test
  public void detectsListingsMissingFilesAndSymlinkChanges() throws Exception {
    var file = write("/work/original", "x");
    var dir = file.getParentDirectory();
    var directoryKey =
        DirectoryListingStateValue.key(
            RootedPath.toRootedPath(Root.absoluteRoot(fs), dir.asFragment()));
    graph.createIfAbsentBatch(null, Reason.OTHER, ImmutableList.of(directoryKey));
    var missing = track(fs.getPath("/work/new"));
    var link = fs.getPath("/work/link");
    link.createSymbolicLink(file);
    var linkKey = track(link);
    String generation = prepare();
    write("/work/new", "new");
    link.delete();
    link.createSymbolicLink(fs.getPath("/work/new"));
    lifecycle.beginReconciliation(generation);
    assertThat(lifecycle.changedKeys()).containsAtLeast(directoryKey, missing, linkKey);
  }

  @Test
  public void directoryStateAndListingHaveIndependentFingerprints() throws Exception {
    var dir = write("/work/original", "x").getParentDirectory();
    var stateKey = track(dir);
    var listingKey = DirectoryListingStateValue.key(stateKey);
    graph.createIfAbsentBatch(null, Reason.OTHER, ImmutableList.of(listingKey));
    String generation = prepare();
    write("/work/added", "new");
    lifecycle.beginReconciliation(generation);
    assertThat(lifecycle.changedKeys()).containsExactly(listingKey);
    assertThat(lifecycle.status().get("changed_observations").toString())
        .contains("DIRECTORY_LISTING");
    assertThat(lifecycle.status().get("changed_observations").toString()).contains("/work");
  }

  @Test
  public void directoryMetadataAloneDoesNotChangeStateOrListing() throws Exception {
    var dir = write("/work/original", "x").getParentDirectory();
    var stateKey = track(dir);
    var listingKey = DirectoryListingStateValue.key(stateKey);
    graph.createIfAbsentBatch(null, Reason.OTHER, ImmutableList.of(listingKey));
    String generation = prepare();
    dir.setLastModifiedTime(12345);
    lifecycle.beginReconciliation(generation);
    assertThat(lifecycle.changedKeys()).isEmpty();
  }

  @Test
  public void recursiveOutputFingerprintIncludesUnchangedMetadataChildren() throws Exception {
    var file = write("/outputs/tree/child", "alpha");
    var directory = file.getParentDirectory();
    var shallow = SharedGraphLifecycle.fingerprint(directory, false);
    var recursive = SharedGraphLifecycle.fingerprint(directory, true);
    FileSystemUtils.writeContentAsLatin1(file, "bravo");
    assertThat(SharedGraphLifecycle.fingerprint(directory, false)).isEqualTo(shallow);
    assertThat(SharedGraphLifecycle.fingerprint(directory, true)).isNotEqualTo(recursive);
  }

  @Test
  public void failedReconciliationStaysBlockedAndCanRetryOrDiscard() throws Exception {
    track(write("/work/file", "x"));
    String generation = prepare();
    lifecycle.beginReconciliation(generation);
    lifecycle.failReconciliation("injected interruption");
    assertThat(lifecycle.commandRejection("query")).contains("PREPARED");
    assertThat(lifecycle.commandRejection("shared_graph")).isNull();
    assertThat(lifecycle.commandRejection("shutdown")).isNull();
    lifecycle.beginReconciliation(generation);
    assertThat(lifecycle.changedKeys()).isEmpty();
    lifecycle.finishReconciliation(0);
    lifecycle.fallback();
    assertThat(lifecycle.enabled()).isFalse();
    assertThat(lifecycle.status().get("generation")).isNull();
    assertThat(lifecycle.commandRejection("query")).isNull();
  }

  @Test
  public void failedBuildOrInterveningCommandCannotPrepare() throws Exception {
    track(write("/work/file", "x"));
    lifecycle.noteCommandResult("build", false);
    assertThrows(IllegalStateException.class, () -> lifecycle.prepare(graph, "", ""));
    lifecycle.noteCommandResult("build", true);
    lifecycle.commandRejection("query");
    assertThrows(IllegalStateException.class, () -> lifecycle.prepare(graph, "", ""));
    lifecycle.noteCommandResult("query", true);
    assertThrows(IllegalStateException.class, () -> lifecycle.prepare(graph, "", ""));
    lifecycle.noteCommandResult("build", true);
    Thread.currentThread().interrupt();
    assertThrows(InterruptedException.class, () -> lifecycle.prepare(graph, "", ""));
    assertThat(Thread.interrupted()).isFalse();
    assertThat(lifecycle.status().get("state")).isEqualTo("READY");
    lifecycle.prepare(graph, "", "");
    assertThat(lifecycle.status().get("state")).isEqualTo("PREPARED");
  }
}
