// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.testing.GcFinalization;
import com.google.devtools.build.skyframe.GraphTester.StringValue;
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Integrity, bounds, lifetime and retained-storage checks for snapshot-coupled mapped edges. */
@RunWith(TestParameterInjector.class)
public final class MappedGraphStoreTest {
  private static final String REVISION = "fda97de2b3b179ecdfa8af455fffde8d0b6b6422";
  private final LayeredInMemoryGraph graph = new LayeredInMemoryGraph(false);

  @After
  public void cleanup() {
    graph.cleanupInterningPools();
  }

  private Path directory() throws IOException {
    return Files.createTempDirectory(Path.of(System.getenv("TEST_TMPDIR")), "mapped-test-");
  }

  private LayeredNodeEntry complete() throws Exception {
    var key = GraphTester.skyKey("a");
    graph.createIfAbsentBatch(null, Reason.OTHER, ImmutableList.of(key));
    var entry = (LayeredNodeEntry) graph.getIfPresent(key);
    entry.addReverseDepAndCheckIfDone(GraphTester.skyKey("parent1"));
    entry.addReverseDepAndCheckIfDone(GraphTester.skyKey("parent2"));
    entry.markRebuilding();
    var b = GraphTester.skyKey("b");
    var c = GraphTester.skyKey("c");
    entry.addTemporaryDirectDepGroup(ImmutableList.of(b, c));
    entry.signalDep(IntVersion.of(1), b);
    entry.signalDep(IntVersion.of(1), c);
    entry.setValue(new StringValue("a"), IntVersion.of(1), null);
    return entry;
  }

  @Test
  public void mappedTraversalDoesNotMaterializeAndFileNeverChanges() throws Exception {
    var entry = complete();
    graph.freezeMappedAtQuiescentBoundary(directory(), REVISION);
    var file = graph.backingFile();
    assertThat(((MappedGraphStore) graph.baselineStoreForTesting()).isReadOnlyForTesting())
        .isTrue();
    byte[] original = Files.readAllBytes(file);
    var deps = entry.getDoneDeps();
    assertThat(deps).isInstanceOf(MappedDoneDeps.class);
    assertThat(graph.mappedBytes()).isEqualTo(original.length);
    assertThat(deps.groupCount()).isEqualTo(1);
    assertThat(deps.dependencyCount()).isEqualTo(2);
    assertThat(deps).containsExactly(GraphTester.skyKey("b"), GraphTester.skyKey("c")).inOrder();
    assertThat(deps.groups())
        .containsExactly(ImmutableList.of(GraphTester.skyKey("b"), GraphTester.skyKey("c")));
    assertThat(entry.getReverseDepsForDoneEntry()).hasSize(2);
    assertThat(graph.materializedDependencyCount()).isEqualTo(0);
    entry.getCompressedDirectDepsForDoneEntry();
    assertThat(graph.materializedDependencyCount()).isEqualTo(2);
    entry.markDirty(DirtyType.CHANGE);
    assertThat(graph.materializedDependencyCount()).isEqualTo(4);
    assertThat(graph.copiedMappedBytes()).isEqualTo(32);
    entry.addReverseDepAndCheckIfDone(null);
    entry.markRebuilding();
    entry.setValue(new StringValue("replacement"), IntVersion.of(2), null);
    assertThat(Files.readAllBytes(file)).isEqualTo(original);
    assertThat(deps).containsExactly(GraphTester.skyKey("b"), GraphTester.skyKey("c")).inOrder();
  }

  @Test
  public void replacedArraysAndDelegatesAreCollectable() throws Exception {
    var entry = complete();
    var compressed = new WeakReference<>(entry.getCompressedDirectDepsForDoneEntry());
    var delegate = oldStorage(entry);
    graph.freezeMappedAtQuiescentBoundary(directory(), REVISION, true);
    GcFinalization.awaitClear(compressed);
    GcFinalization.awaitClear(delegate);
    var audit = graph.finishRetentionAudit();
    assertThat(((java.util.Map<?, ?>) audit.get("direct_array")).get("surviving_references"))
        .isEqualTo(0L);
    assertThat(((java.util.Map<?, ?>) audit.get("native_delegate")).get("surviving_references"))
        .isEqualTo(0L);
    assertThat(entry.getDoneDeps()).hasSize(2);
  }

  @Test
  public void retentionAuditReportsAnExternallyRetainedArray() throws Exception {
    var entry = complete();
    Object held = entry.getCompressedDirectDepsForDoneEntry();
    var delegate = oldStorage(entry);
    graph.freezeMappedAtQuiescentBoundary(directory(), REVISION, true);
    GcFinalization.awaitClear(delegate);
    var audit = graph.finishRetentionAudit();
    assertThat(((java.util.Map<?, ?>) audit.get("direct_array")).get("surviving_references"))
        .isEqualTo(1L);
    java.lang.ref.Reference.reachabilityFence(held);
    assertThat(graph.finishRetentionAudit()).isEmpty();
  }

  private static WeakReference<Object> oldStorage(LayeredNodeEntry entry) {
    return new WeakReference<>(entry.stageFreeze().original());
  }

  @Test
  public void retainedReaderSurvivesGenerationRetirement() throws Exception {
    var entry = complete();
    graph.freezeMappedAtQuiescentBoundary(directory(), REVISION);
    var reader = entry.getDoneDeps();
    var reverse = entry.getReverseDepsForDoneEntry();
    var file = graph.backingFile();
    graph.cleanupInterningPools();
    assertThat(graph.baselineGeneration()).isNull();
    assertThat(graph.mappedBytes()).isEqualTo(0);
    assertThat(reader).containsExactly(GraphTester.skyKey("b"), GraphTester.skyKey("c")).inOrder();
    assertThat(reverse).hasSize(2);
    assertThat(Files.exists(file)).isTrue();
  }

  @Test
  public void failedConstructionLeavesMutableGraphUsable() throws Exception {
    var entry = complete();
    Path regularFile = Files.createTempFile(directory(), "not-a-directory", ".txt");
    assertThrows(
        IOException.class, () -> graph.freezeMappedAtQuiescentBoundary(regularFile, REVISION));
    assertThat(entry.isFrozen()).isFalse();
    assertThat(entry.getValue()).isEqualTo(new StringValue("a"));
    assertThat(graph.baselineGeneration()).isNull();
    graph.freezeMappedAtQuiescentBoundary(directory(), REVISION);
    assertThat(entry.isFrozen()).isTrue();
  }

  private enum Corruption {
    CHECKSUM,
    TRUNCATED,
    TRAILING,
    FORMAT,
    SNAPSHOT_FLAG,
    RESERVED,
    NODE_HANDLE,
    OFFSET_OVERFLOW,
    NEGATIVE_COUNT,
    GROUP_BOUNDARY,
    KEY_HANDLE,
    DUPLICATE_REVERSE
  }

  @Test
  public void rejectsCorruptionBeforeActivation(@TestParameter Corruption corruption)
      throws Exception {
    complete();
    graph.freezeMappedAtQuiescentBoundary(directory(), REVISION);
    var store = (MappedGraphStore) graph.baselineStoreForTesting();
    byte[] bytes = Files.readAllBytes(graph.backingFile());
    var buffer = ByteBuffer.wrap(bytes);
    long direct = buffer.getLong(MappedGraphStore.HEADER_BYTES + 16);
    long reverse = buffer.getLong(MappedGraphStore.HEADER_BYTES + 32);
    switch (corruption) {
      case CHECKSUM -> bytes[bytes.length - 1] ^= 1;
      case TRUNCATED -> bytes = Arrays.copyOf(bytes, bytes.length - 1);
      case TRAILING -> bytes = Arrays.copyOf(bytes, bytes.length + 4);
      case FORMAT -> buffer.putInt(8, 99);
      case SNAPSHOT_FLAG -> buffer.putInt(32, 0);
      case RESERVED -> bytes[150] = 1;
      case NODE_HANDLE -> buffer.putInt(MappedGraphStore.HEADER_BYTES + 4, Integer.MAX_VALUE);
      case OFFSET_OVERFLOW -> buffer.putLong(MappedGraphStore.HEADER_BYTES + 16, Long.MAX_VALUE);
      case NEGATIVE_COUNT -> buffer.putInt((int) direct + 4, -1);
      case GROUP_BOUNDARY -> buffer.putInt((int) direct + 8, Integer.MAX_VALUE);
      case KEY_HANDLE -> buffer.putInt((int) direct + 12, Integer.MAX_VALUE);
      case DUPLICATE_REVERSE -> buffer.putInt((int) reverse + 8, buffer.getInt((int) reverse + 4));
    }
    if (corruption != Corruption.CHECKSUM) {
      rewriteChecksum(bytes);
    }
    Path corrupt = Files.write(directory().resolve("corrupt.arena"), bytes);
    assertThrows(
        IOException.class,
        () ->
            MappedGraphStore.openValidated(
                corrupt, store.generation(), REVISION, store.size(), store.keyTableForTesting()));
  }

  @Test
  public void restoreVerificationRejectsChangedArenaEvenWithValidNewChecksum() throws Exception {
    complete();
    graph.freezeMappedAtQuiescentBoundary(directory(), REVISION);
    graph.verifyBaseline();
    Path file = graph.backingFile();
    byte[] bytes = Files.readAllBytes(file);
    var buffer = ByteBuffer.wrap(bytes);
    int direct = (int) buffer.getLong(MappedGraphStore.HEADER_BYTES + 16);
    int first = buffer.getInt(direct + 12);
    buffer.putInt(direct + 12, buffer.getInt(direct + 16));
    buffer.putInt(direct + 16, first);
    rewriteChecksum(bytes);
    Files.setPosixFilePermissions(
        file,
        java.util.Set.of(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
    try (var channel =
        java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.WRITE)) {
      var content = ByteBuffer.wrap(bytes);
      while (content.hasRemaining()) {
        channel.write(content);
      }
    }
    assertThrows(IOException.class, graph::verifyBaseline);
  }

  @Test
  public void rejectsWrongGenerationRevisionOrJavaTables() throws Exception {
    complete();
    graph.freezeMappedAtQuiescentBoundary(directory(), REVISION);
    var store = (MappedGraphStore) graph.baselineStoreForTesting();
    Path file = graph.backingFile();
    assertThrows(
        IOException.class,
        () ->
            MappedGraphStore.openValidated(
                file, UUID.randomUUID(), REVISION, store.size(), store.keyTableForTesting()));
    assertThrows(
        IOException.class,
        () ->
            MappedGraphStore.openValidated(
                file,
                store.generation(),
                "0000000000000000000000000000000000000000",
                store.size(),
                store.keyTableForTesting()));
    assertThrows(
        IOException.class,
        () ->
            MappedGraphStore.openValidated(
                file, store.generation(), REVISION, store.size(), new SkyKey[0]));
  }

  private static void rewriteChecksum(byte[] bytes) throws Exception {
    int start = MappedGraphStore.CHECKSUM_OFFSET;
    Arrays.fill(bytes, start, start + 32, (byte) 0);
    byte[] checksum = MessageDigest.getInstance("SHA-256").digest(bytes);
    System.arraycopy(checksum, 0, bytes, start, checksum.length);
  }
}
