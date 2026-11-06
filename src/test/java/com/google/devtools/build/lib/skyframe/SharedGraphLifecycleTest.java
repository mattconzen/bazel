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
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Command barrier, exact generation, retry and content-verification contracts. */
@RunWith(JUnit4.class)
public final class SharedGraphLifecycleTest {
  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();
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
  public void verifiedCleanCloneRequiresPreparedOptInAndRecordsNoContentReads() throws Exception {
    track(write("/work/input", "alpha"));
    lifecycle.noteCommandResult("build", true);
    lifecycle.prepare(graph, "", "", false, ImmutableList.of(), true);
    String generation = (String) lifecycle.status().get("generation");
    assertThrows(IllegalStateException.class, () -> lifecycle.beginReconciliation("other"));
    lifecycle.beginReconciliation(generation);
    assertThat(lifecycle.unchangedKeysFromVerifiedClone()).isEmpty();
    assertThat(lifecycle.status().get("fingerprint_io").toString())
        .contains("observations_verified_by_clone_continuity=1");
    assertThat(lifecycle.status().get("reconciliation_change_evidence"))
        .isEqualTo("controller-verified-unchanged-filesystem-clone");
    lifecycle.finishReconciliation(0);
    assertThat(lifecycle.commandRejection("build")).isNull();
    lifecycle.retire();
    assertThat(lifecycle.status().get("allow_controller_verified_clean_clone")).isEqualTo(false);
  }

  @Test
  public void unpermittedCloneAssertionFailsClosedAndFullRevalidationStillDetectsEdits()
      throws Exception {
    var file = write("/work/input", "alpha");
    var key = track(file);
    String generation = prepare();
    FileSystemUtils.writeContentAsLatin1(file, "bravo");
    lifecycle.beginReconciliation(generation);
    assertThrows(IllegalStateException.class, lifecycle::unchangedKeysFromVerifiedClone);
    assertThat(lifecycle.commandRejection("build")).isNotNull();
    lifecycle.failReconciliation("Unpermitted clone assertion");
    lifecycle.beginReconciliation(generation);
    assertThat(lifecycle.changedKeys()).containsExactly(key);
  }

  @Test
  public void optedInGenerationStillUsesExactContentChecksWithoutControllerAssertion()
      throws Exception {
    var file = write("/work/input", "alpha");
    var key = track(file);
    lifecycle.noteCommandResult("build", true);
    lifecycle.prepare(graph, "", "", false, ImmutableList.of(), true);
    FileSystemUtils.writeContentAsLatin1(file, "bravo");
    lifecycle.beginReconciliation((String) lifecycle.status().get("generation"));
    assertThat(lifecycle.changedKeys()).containsExactly(key);
    assertThat(lifecycle.status().get("reconciliation_change_evidence"))
        .isEqualTo("full-mutable-content-and-immutable-root-proof");
  }

  @Test
  public void cleanCloneAssertionDoesNotBypassLostImmutableRootProof() throws Exception {
    boolean[] readonly = {true};
    var target =
        new SharedGraphLifecycle(
            "heap",
            name -> {
              if (!readonly[0]) {
                throw new java.io.IOException("Read-only root proof lost");
              }
              return java.nio.file.Path.of(name);
            });
    track(write("/immutable/input", "alpha"));
    target.noteCommandResult("build", true);
    target.prepare(graph, "", "", false, ImmutableList.of("/immutable"), true);
    readonly[0] = false;
    target.beginReconciliation((String) target.status().get("generation"));
    assertThrows(java.io.IOException.class, target::unchangedKeysFromVerifiedClone);
    assertThat(target.commandRejection("build")).isNotNull();
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
    assertThat(lifecycle.status().get("phase_metrics").toString())
        .contains("prepare_capture_first");
    assertThat(lifecycle.status().get("phase_metrics_scope").toString())
        .contains("not retained bytes or private physical pages");
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
  public void verificationPassReusesBoundedBufferWithoutHashingStaleBytes() throws Exception {
    var large = write("/work/a-large", "x".repeat(140_000));
    var largeKey = track(large);
    track(write("/work/b-small", "short"));
    track(write("/work/c-empty", ""));
    String generation = prepare();
    var io = (java.util.Map<?, ?>) lifecycle.status().get("fingerprint_io");
    var first = (java.util.Map<?, ?>) io.get("prepare_capture_first");
    assertThat(first.get("regular_files")).isEqualTo(3L);
    assertThat(first.get("buffer_bytes_allocated")).isEqualTo(65_536L);
    assertThat(first.get("content_bytes_read")).isEqualTo(140_005L);
    FileSystemUtils.writeContentAsLatin1(large, "x".repeat(65_537) + "y" + "x".repeat(74_462));
    lifecycle.beginReconciliation(generation);
    // Reusing the previous large file's buffer must not affect short/empty fingerprints.
    assertThat(lifecycle.changedKeys()).containsExactly(largeKey);
  }

  @Test
  public void immutableInputsSkipContentWhileMutableInputsKeepExactChecks() throws Exception {
    track(write("/immutable/toolchain", "x".repeat(140_000)));
    var mutable = write("/work/input", "alpha");
    var mutableKey = track(mutable);
    var target = new SharedGraphLifecycle("heap", java.nio.file.Path::of);
    target.noteCommandResult("build", true);
    target.prepare(graph, "", "", false, ImmutableList.of("/immutable"));
    var io = (java.util.Map<?, ?>) target.status().get("fingerprint_io");
    var first = (java.util.Map<?, ?>) io.get("prepare_capture_first");
    assertThat(first.get("content_bytes_read")).isEqualTo(5L);
    assertThat(first.get("immutable_files_content_skipped")).isEqualTo(1L);
    FileSystemUtils.writeContentAsLatin1(mutable, "bravo");
    target.beginReconciliation((String) target.status().get("generation"));
    assertThat(target.changedKeys()).containsExactly(mutableKey);
    var reconciled =
        (java.util.Map<?, ?>)
            ((java.util.Map<?, ?>) target.status().get("fingerprint_io"))
                .get("reconcile_fingerprints");
    assertThat(reconciled.get("content_bytes_read")).isEqualTo(5L);
    assertThat(reconciled.get("immutable_files_content_skipped")).isEqualTo(1L);
  }

  @Test
  public void immutableRootRequiresReadonlyProofAndConsumesPreparationToken() throws Exception {
    track(write("/immutable/toolchain", "x"));
    var target =
        new SharedGraphLifecycle(
            "heap",
            path -> {
              throw new java.io.IOException("not read-only");
            });
    target.noteCommandResult("build", true);
    assertThrows(
        java.io.IOException.class,
        () -> target.prepare(graph, "", "", false, ImmutableList.of("/immutable")));
    assertThat(graph.baselineGeneration()).isNull();
    assertThat(target.status().get("state")).isEqualTo("READY");
    assertThrows(IllegalStateException.class, () -> target.prepare(graph, "", ""));
  }

  @Test
  public void losingReadonlyProofBlocksRestoreAndFallbackClearsRoots() throws Exception {
    track(write("/immutable/toolchain", "x"));
    var readonly = new java.util.concurrent.atomic.AtomicBoolean(true);
    var target =
        new SharedGraphLifecycle(
            "heap",
            path -> {
              if (!readonly.get()) {
                throw new java.io.IOException("lost read-only mount");
              }
              return java.nio.file.Path.of(path);
            });
    target.noteCommandResult("build", true);
    target.prepare(graph, "", "", false, ImmutableList.of("/immutable"));
    readonly.set(false);
    target.beginReconciliation((String) target.status().get("generation"));
    assertThrows(java.io.IOException.class, target::changedKeys);
    target.failReconciliation("lost read-only mount");
    assertThat(target.commandRejection("build")).contains("PREPARED");
    target.fallback();
    assertThat(target.status().get("immutable_roots")).isEqualTo(ImmutableList.of());
  }

  @Test
  public void escapedCanonicalPathAndSiblingPrefixStillRequireContentChecks() throws Exception {
    var escaped = write("/immutable/escaped", "alpha");
    var sibling = write("/immutable-other/file", "alpha");
    var escapedKey = track(escaped);
    var siblingKey = track(sibling);
    var target =
        new SharedGraphLifecycle(
            "heap",
            path ->
                java.nio.file.Path.of(
                    path.equals("/immutable/escaped") ? "/outside/escaped" : path));
    target.noteCommandResult("build", true);
    target.prepare(graph, "", "", false, ImmutableList.of("/immutable"));
    FileSystemUtils.writeContentAsLatin1(escaped, "bravo");
    FileSystemUtils.writeContentAsLatin1(sibling, "bravo");
    target.beginReconciliation((String) target.status().get("generation"));
    assertThat(target.changedKeys()).containsExactly(escapedKey, siblingKey);
  }

  @Test
  public void aliasesBetweenImmutableRootsCanSkipContent() throws Exception {
    track(write("/external/repository/toolchain", "x".repeat(140_000)));
    var target =
        new SharedGraphLifecycle(
            "heap",
            path ->
                java.nio.file.Path.of(
                    path.equals("/external/repository/toolchain")
                        ? "/repository-cache/toolchain"
                        : path));
    target.noteCommandResult("build", true);
    target.prepare(graph, "", "", false, ImmutableList.of("/external", "/repository-cache"));
    var io = (java.util.Map<?, ?>) target.status().get("fingerprint_io");
    var first = (java.util.Map<?, ?>) io.get("prepare_capture_first");
    assertThat(first.get("content_bytes_read")).isEqualTo(0L);
    assertThat(first.get("buffer_bytes_allocated")).isEqualTo(0L);
    assertThat(first.get("immutable_files_content_skipped")).isEqualTo(1L);
  }

  @Test
  public void nestedWritableFileFallsBackToFullContentVerification() throws Exception {
    var file = write("/immutable/nested/file", "alpha");
    var key = track(file);
    var target =
        new SharedGraphLifecycle(
            "heap",
            path -> {
              if (path.equals("/immutable/nested/file")) {
                throw new java.io.IOException("nested writable mount");
              }
              return java.nio.file.Path.of(path);
            });
    target.noteCommandResult("build", true);
    target.prepare(graph, "", "", false, ImmutableList.of("/immutable"));
    FileSystemUtils.writeContentAsLatin1(file, "bravo");
    target.beginReconciliation((String) target.status().get("generation"));
    assertThat(target.changedKeys()).containsExactly(key);
    var io = (java.util.Map<?, ?>) target.status().get("fingerprint_io");
    var first = (java.util.Map<?, ?>) io.get("prepare_capture_first");
    assertThat(first.get("immutable_files_content_skipped")).isEqualTo(0L);
    assertThat(first.get("content_bytes_read")).isEqualTo(5L);
  }

  @Test
  public void readonlyLinuxMountProofUsesPerMountFlagsAndNestedViews() throws Exception {
    String mounts =
        "24 1 8:1 / / rw,relatime - ext4 /dev/vda rw\n"
            + "25 24 8:1 /tools /immutable ro,relatime - ext4 /dev/vda rw\n"
            + "26 25 8:1 /writable /immutable/nested rw,relatime - ext4 /dev/vda rw\n"
            + "27 24 8:1 /space /tool\\040cache ro,relatime - ext4 /dev/vda rw\n";
    assertThat(
            SharedGraphLifecycle.readonlyLinuxMount(
                java.nio.file.Path.of("/immutable/tool"), mounts))
        .isTrue();
    assertThat(
            SharedGraphLifecycle.readonlyLinuxMount(
                java.nio.file.Path.of("/immutable/nested/file"), mounts))
        .isFalse();
    assertThat(
            SharedGraphLifecycle.readonlyLinuxMount(
                java.nio.file.Path.of("/immutable-other/file"), mounts))
        .isFalse();
    assertThat(
            SharedGraphLifecycle.readonlyLinuxMount(
                java.nio.file.Path.of("/tool cache/file"), mounts))
        .isTrue();
    String overmounted = mounts + "28 24 8:1 /other /immutable rw,relatime - ext4 /dev/vda rw\n";
    assertThat(
            SharedGraphLifecycle.readonlyLinuxMount(
                java.nio.file.Path.of("/immutable/tool"), overmounted))
        .isFalse();
  }

  @Test
  public void malformedOrMissingMountProofCannotDeclareAnImmutableFile() throws Exception {
    assertThat(
            SharedGraphLifecycle.readonlyLinuxMount(java.nio.file.Path.of("/immutable/tool"), ""))
        .isFalse();
    assertThrows(
        java.io.IOException.class,
        () ->
            SharedGraphLifecycle.readonlyLinuxMount(
                java.nio.file.Path.of("/immutable/tool"), "truncated\n"));
  }

  @Test
  public void constructionFailureReopensAdmissionWithoutCommittedBaseline() throws Exception {
    track(write("/work/file", "x"));
    var target = new SharedGraphLifecycle("mapped");
    target.noteCommandResult("build", true);
    String directory = temporaryFolder.newFile("not-a-directory").getAbsolutePath();
    assertThrows(java.io.IOException.class, () -> target.prepare(graph, directory, "0".repeat(40)));
    assertThat(graph.baselineGeneration()).isNull();
    assertThat(target.status().get("state")).isEqualTo("READY");
    String retryDirectory = temporaryFolder.newFolder("retry-arena").getAbsolutePath();
    assertThrows(
        IllegalStateException.class, () -> target.prepare(graph, retryDirectory, "0".repeat(40)));
    assertThat(target.commandRejection("query")).isNull();
    target.noteCommandResult("build", true);
    target.prepare(graph, retryDirectory, "0".repeat(40));
    assertThat(target.status().get("preparation_complete")).isEqualTo(true);
    assertThat(target.status().get("state")).isEqualTo("PREPARED");
  }

  @Test
  public void postHeapFreezeFailureBlocksCommandsAndRequiresDiscard() throws Exception {
    assertPostFreezeFailure(lifecycle, "");
  }

  @Test
  public void postMappedFreezeFailureBlocksCommandsAndRequiresDiscard() throws Exception {
    assertPostFreezeFailure(
        new SharedGraphLifecycle("mapped"), temporaryFolder.newFolder("arena").getAbsolutePath());
  }

  private void assertPostFreezeFailure(SharedGraphLifecycle target, String directory)
      throws Exception {
    track(write("/work/file", "x"));
    target.noteCommandResult("build", true);
    var injected = new OutOfMemoryError("injected after freeze, before lifecycle publication");
    assertThat(
            assertThrows(
                OutOfMemoryError.class,
                () ->
                    target.prepare(
                        graph,
                        directory,
                        "0".repeat(40),
                        false,
                        () -> {
                          throw injected;
                        })))
        .isSameInstanceAs(injected);
    assertThat(graph.baselineGeneration()).isNotNull();
    assertThat(target.status().get("state")).isEqualTo("PREPARED");
    assertThat(target.status().get("preparation_complete")).isEqualTo(false);
    assertThat(target.status().get("failure").toString()).contains("discard is required");
    assertThat(target.commandRejection("query")).contains("PREPARED");
    assertThat(target.commandRejection("shared_graph")).isNull();
    assertThat(target.commandRejection("shutdown")).isNull();
    String generation = target.status().get("generation").toString();
    assertThrows(IllegalStateException.class, () -> target.beginReconciliation(generation));
    assertThat(target.status().get("state")).isEqualTo("PREPARED");
    target.fallback();
    assertThat(target.status().get("generation")).isNull();
    assertThat(target.commandRejection("build")).isNull();
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
    assertThrows(IllegalStateException.class, () -> lifecycle.prepare(graph, "", ""));
    lifecycle.noteCommandResult("build", true);
    lifecycle.prepare(graph, "", "");
    assertThat(lifecycle.status().get("state")).isEqualTo("PREPARED");
  }
}
