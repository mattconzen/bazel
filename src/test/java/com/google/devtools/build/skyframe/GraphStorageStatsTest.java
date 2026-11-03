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
import com.google.devtools.build.skyframe.NodeEntry.DirtyType;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies inventories do not decode vectors or promote nodes. */
@RunWith(TestParameterInjector.class)
public final class GraphStorageStatsTest {
  private enum Backend {
    NATIVE,
    HEAP,
    MAPPED
  }

  @Test
  public void inventoriesOnlyOwnedArraysAndRetainedValueCounts(@TestParameter Backend backend)
      throws Exception {
    InMemoryGraph graph =
        backend == Backend.NATIVE ? InMemoryGraph.create(false) : new LayeredInMemoryGraph(false);
    try {
      var a = GraphTester.skyKey("a");
      var b = GraphTester.skyKey("b");
      var c = GraphTester.skyKey("c");
      graph.createIfAbsentBatch(null, Reason.OTHER, ImmutableList.of(a, b, c));
      var entry = graph.getIfPresent(a);
      entry.addReverseDepAndCheckIfDone(null);
      entry.markRebuilding();
      entry.addTemporaryDirectDepGroup(ImmutableList.of(b, c));
      entry.signalDep(IntVersion.of(1), b);
      entry.signalDep(IntVersion.of(1), c);
      entry.setValue(new StringValue("a"), IntVersion.of(1), null);
      if (graph instanceof LayeredInMemoryGraph layered) {
        if (backend == Backend.MAPPED) {
          layered.freezeMappedAtQuiescentBoundary(
              Files.createTempDirectory(Path.of(System.getenv("TEST_TMPDIR")), "inventory-"),
              "fda97de2b3b179ecdfa8af455fffde8d0b6b6422");
        } else {
          layered.freezeAtQuiescentBoundary();
        }
      }
      var stats = GraphStorageStats.collect(graph);
      assertThat(stats.nodes()).isEqualTo(3);
      assertThat(stats.doneNodes()).isEqualTo(1);
      assertThat(stats.dirtyNodes()).isEqualTo(2);
      assertThat(stats.directDependencies()).isEqualTo(2);
      assertThat(stats.directGroups()).isEqualTo(1);
      assertThat(stats.heapDependencyArrays()).isEqualTo(backend == Backend.MAPPED ? 0 : 1);
      assertThat(stats.heapDependencyArrayReferenceSlots())
          .isEqualTo(backend == Backend.MAPPED ? 0 : 3);
      assertThat(stats.valueNodeCountsByClass()).containsExactly(StringValue.class.getName(), 1L);
      assertThat(stats.materializedDependencies()).isEqualTo(0);
      assertThat(stats.promotedDependencies()).isEqualTo(0);
      assertThat(stats.promotionsByReason()).isEmpty();
      entry.markDirty(DirtyType.CHANGE);
      var dirty = GraphStorageStats.collect(graph);
      assertThat(dirty.doneNodes()).isEqualTo(0);
      assertThat(dirty.dirtyNodes()).isEqualTo(3);
      assertThat(dirty.directDependencies()).isEqualTo(0);
      assertThat(dirty.valueNodeCountsByClass()).containsExactly(StringValue.class.getName(), 1L);
      if (backend == Backend.MAPPED) {
        assertThat(dirty.materializedDependencies()).isEqualTo(2);
        assertThat(dirty.promotedDependencies()).isEqualTo(2);
      }
      Thread.currentThread().interrupt();
      assertThrows(InterruptedException.class, () -> GraphStorageStats.collect(graph));
      assertThat(Thread.interrupted()).isFalse();
    } finally {
      graph.cleanupInterningPools();
    }
  }
}
