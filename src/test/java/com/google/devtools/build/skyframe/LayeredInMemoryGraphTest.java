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
import com.google.devtools.build.skyframe.GraphTester.StringValue;
import com.google.devtools.build.skyframe.NodeEntry.DependencyState;
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Freeze, membership, recovery and concurrent promotion regression cases. */
@RunWith(TestParameterInjector.class)
public final class LayeredInMemoryGraphTest {
  private enum Backend {
    HEAP,
    MAPPED
  }

  @TestParameter private Backend backend;

  private UUID freeze() throws Exception {
    if (backend == Backend.HEAP) {
      return graph.freezeAtQuiescentBoundary();
    }
    return graph.freezeMappedAtQuiescentBoundary(
        Files.createTempDirectory(Path.of(System.getenv("TEST_TMPDIR")), "arena-"),
        "fda97de2b3b179ecdfa8af455fffde8d0b6b6422");
  }

  private final LayeredInMemoryGraph graph = new LayeredInMemoryGraph(false);

  @After
  public void cleanup() {
    graph.cleanupInterningPools();
  }

  private LayeredNodeEntry entry(String name) {
    SkyKey key = GraphTester.skyKey(name);
    graph.createIfAbsentBatch(null, Reason.OTHER, ImmutableList.of(key));
    return (LayeredNodeEntry) graph.getIfPresent(key);
  }

  private LayeredNodeEntry complete(String name) throws InterruptedException {
    var entry = entry(name);
    entry.addReverseDepAndCheckIfDone(null);
    entry.markRebuilding();
    entry.setValue(new StringValue(name), IntVersion.of(1), null);
    return entry;
  }

  @Test
  public void freezePreservesStableIdentityAndReadOnlyViews() throws Exception {
    var entry = complete("a");
    var wrapped = entry.getValueMaybeWithMetadata();
    var values = graph.getValues();
    var done = graph.getDoneValues();
    var all = graph.getAllNodeEntries();
    var generation = freeze();
    assertThat(graph.baselineGeneration()).isEqualTo(generation);
    assertThat(graph.baselineNodeCount()).isEqualTo(1);
    assertThat(entry.isFrozen()).isTrue();
    assertThat(graph.getIfPresent(entry.getKey())).isSameInstanceAs(entry);
    assertThat(entry.getValueMaybeWithMetadata()).isSameInstanceAs(wrapped);
    assertThat(values.get(entry.getKey())).isSameInstanceAs(wrapped);
    assertThat(done).hasSize(1);
    assertThat(all).containsExactly(entry);
    assertThat(entry.getDoneDeps()).isEmpty();
    assertThat(entry.getReverseDepsForDoneEntry()).isEmpty();
    assertThat(graph.promotionsByReason()).isEmpty();
    entry.markDirty(DirtyType.CHANGE);
    assertThat(entry.isFrozen()).isFalse();
    assertThat(entry.getValueMaybeWithMetadata()).isNull();
    assertThat(entry.toValue()).isSameInstanceAs(wrapped);
    assertThat(values.get(entry.getKey())).isSameInstanceAs(wrapped);
    assertThat(done).isEmpty();
    assertThat(all).containsExactly(entry);
    assertThat(graph.promotionsByReason()).containsExactly("markDirty", 1L);
  }

  @Test
  public void completedReadAndDeletionAccessorsDoNotPromote() throws Exception {
    var entry = complete("a");
    var parent = GraphTester.skyKey("parent");
    entry.addReverseDepAndCheckIfDone(parent);
    freeze();
    assertThat(entry.addReverseDepAndCheckIfDone(null)).isEqualTo(DependencyState.DONE);
    assertThat(entry.checkIfDoneForDirtyReverseDep(parent)).isEqualTo(DependencyState.DONE);
    assertThrows(NullPointerException.class, () -> entry.checkIfDoneForDirtyReverseDep(null));
    var reverseDeps = entry.getAllReverseDepsForNodeBeingDeleted();
    graph.remove(entry.getKey());
    assertThat(reverseDeps).containsExactly(parent);
    assertThat(entry.isFrozen()).isTrue();
    assertThat(graph.promotionsByReason()).isEmpty();
  }

  @Test
  public void deleteAndRecreateNeverRestoresBaseline() throws Exception {
    var old = complete("a");
    var key = old.getKey();
    var values = graph.getValues();
    freeze();
    graph.remove(key);
    assertThat(graph.getIfPresent(key)).isNull();
    assertThat(graph.get(null, Reason.OTHER, key)).isNull();
    assertThat(graph.getBatchMap(null, Reason.OTHER, ImmutableList.of(key))).isEmpty();
    assertThat(values).isEmpty();
    assertThat(graph.valuesSize()).isEqualTo(0);
    var batch = graph.createIfAbsentBatch(null, Reason.OTHER, ImmutableList.of(key));
    assertThat(graph.getIfPresent(key)).isNotSameInstanceAs(old);
    assertThat(graph.getIfPresent(key).isDone()).isFalse();
    assertThat(graph.getDoneValues()).isEmpty();
    graph.remove(key);
    assertThat(batch.get(key)).isNotNull();
    assertThat(graph.getIfPresent(key).isDone()).isFalse();
  }

  @Test
  public void interruptedFreezeLeavesGraphUsableAndCanRetry() throws Exception {
    var successful = complete("done");
    var dirty = complete("dirty");
    dirty.markDirty(DirtyType.CHANGE);
    var inflight = entry("inflight");
    inflight.addReverseDepAndCheckIfDone(null);
    Thread.currentThread().interrupt();
    assertThrows(InterruptedException.class, this::freeze);
    assertThat(Thread.interrupted()).isFalse();
    assertThat(graph.baselineGeneration()).isNull();
    assertThat(successful.isFrozen()).isFalse();
    freeze();
    assertThat(successful.isFrozen()).isTrue();
    assertThat(dirty.isFrozen()).isFalse();
    assertThat(dirty.isDirty()).isTrue();
    assertThat(inflight.isFrozen()).isFalse();
    assertThat(inflight.isDone()).isFalse();
    assertThat(graph.baselineNodeCount()).isEqualTo(1);
    assertThrows(IllegalStateException.class, this::freeze);
  }

  @Test
  public void partialPublicationFailureRollsBackEveryHandle(
      @TestParameter({"1", "3"}) int failAfter) throws Exception {
    var entries = ImmutableList.of(complete("first"), complete("second"), complete("third"));
    var values = entries.stream().map(LayeredNodeEntry::getValueMaybeWithMetadata).toList();
    Path directory =
        backend == Backend.MAPPED
            ? Files.createTempDirectory(Path.of(System.getenv("TEST_TMPDIR")), "rollback-")
            : null;
    var commits = new AtomicInteger();
    var injected = new OutOfMemoryError("injected during handle publication");
    assertThat(
            assertThrows(
                OutOfMemoryError.class,
                () ->
                    graph.freezeAtQuiescentBoundary(
                        directory,
                        "fda97de2b3b179ecdfa8af455fffde8d0b6b6422",
                        false,
                        () -> {
                          if (commits.incrementAndGet() == failAfter) {
                            throw injected;
                          }
                        })))
        .isSameInstanceAs(injected);
    assertThat(commits.get()).isEqualTo(failAfter);
    assertThat(graph.baselineGeneration()).isNull();
    assertThat(graph.baselineNodeCount()).isEqualTo(0);
    for (int index = 0; index < entries.size(); index++) {
      var entry = entries.get(index);
      assertThat(entry.isFrozen()).isFalse();
      assertThat(graph.getIfPresent(entry.getKey())).isSameInstanceAs(entry);
      assertThat(entry.getValueMaybeWithMetadata()).isSameInstanceAs(values.get(index));
    }
    if (directory != null) {
      try (var files = Files.list(directory)) {
        assertThat(files.toList()).isEmpty();
      }
    }
    freeze();
    assertThat(graph.baselineNodeCount()).isEqualTo(3);
    for (var entry : entries) {
      assertThat(entry.isFrozen()).isTrue();
    }
  }

  @Test
  public void promotionRestoresGroupsVersionsAndConsolidatedReverseEdges() throws Exception {
    var entry = entry("a");
    var b = GraphTester.skyKey("b");
    var c = GraphTester.skyKey("c");
    var d = GraphTester.skyKey("d");
    var parent = GraphTester.skyKey("parent");
    entry.addReverseDepAndCheckIfDone(parent);
    entry.markRebuilding();
    entry.addTemporaryDirectDepGroup(ImmutableList.of(b, c));
    entry.addSingletonTemporaryDirectDep(d);
    for (SkyKey dep : ImmutableList.of(b, c, d)) {
      entry.signalDep(IntVersion.of(1), dep);
    }
    var value = new StringValue("value");
    entry.setValue(value, IntVersion.of(1), null);
    entry.markDirty(DirtyType.CHANGE);
    entry.addReverseDepAndCheckIfDone(null);
    entry.markRebuilding();
    entry.addTemporaryDirectDepGroup(ImmutableList.of(b, c));
    entry.addSingletonTemporaryDirectDep(d);
    for (SkyKey dep : ImmutableList.of(b, c, d)) {
      entry.signalDep(IntVersion.of(2), dep);
    }
    entry.setValue(new StringValue("value"), IntVersion.of(2), null);
    freeze();
    DoneDeps reader = entry.getDoneDeps();
    assertThat(reader.groups())
        .containsExactly(ImmutableList.of(b, c), ImmutableList.of(d))
        .inOrder();
    assertThat(entry.getVersion()).isEqualTo(IntVersion.of(1));
    assertThat(entry.lastEvaluatedVersion()).isEqualTo(IntVersion.of(2));
    entry.addReverseDepAndCheckIfDone(GraphTester.skyKey("other"));
    assertThat(entry.getReverseDepsForDoneEntry())
        .containsExactly(parent, GraphTester.skyKey("other"));
    assertThat(entry.getValue()).isSameInstanceAs(value);
    assertThat(entry.getVersion()).isEqualTo(IntVersion.of(1));
    assertThat(entry.lastEvaluatedVersion()).isEqualTo(IntVersion.of(2));
    assertThat(entry.getDoneDeps().groups()).containsExactlyElementsIn(reader.groups()).inOrder();
    assertThat(graph.copiedDependencyCount()).isEqualTo(4);
  }

  @Test
  public void concurrentPromotionHasOneOwnerAndOneSchedulingResponse() throws Exception {
    var entry = complete("a");
    freeze();
    int threads = 8;
    try (var executor = Executors.newFixedThreadPool(threads)) {
      var barrier = new CyclicBarrier(threads);
      List<Callable<DependencyState>> tasks = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        var parent = GraphTester.skyKey("parent" + i);
        tasks.add(
            () -> {
              barrier.await(10, TimeUnit.SECONDS);
              return entry.addReverseDepAndCheckIfDone(parent);
            });
      }
      for (var future : executor.invokeAll(tasks, 15, TimeUnit.SECONDS)) {
        assertThat(future.get()).isEqualTo(DependencyState.DONE);
      }
      assertThat(graph.promotionsByReason()).containsExactly("addReverseDepAndCheckIfDone", 1L);
      assertThat(entry.getReverseDepsForDoneEntry()).hasSize(threads);
      entry.markDirty(DirtyType.CHANGE);
      var schedulingBarrier = new CyclicBarrier(threads);
      tasks.clear();
      for (int i = 0; i < threads; i++) {
        tasks.add(
            () -> {
              schedulingBarrier.await(10, TimeUnit.SECONDS);
              return entry.addReverseDepAndCheckIfDone(null);
            });
      }
      var results = new ArrayList<DependencyState>();
      for (var future : executor.invokeAll(tasks, 15, TimeUnit.SECONDS)) {
        results.add(future.get());
      }
      assertThat(results.stream().filter(s -> s == DependencyState.NEEDS_SCHEDULING).count())
          .isEqualTo(1);
      assertThat(results.stream().filter(s -> s == DependencyState.ALREADY_EVALUATING).count())
          .isEqualTo(threads - 1);
    }
  }
}
