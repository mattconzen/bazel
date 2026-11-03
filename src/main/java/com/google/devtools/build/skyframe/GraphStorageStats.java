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

import com.google.common.collect.ImmutableMap;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * On-demand logical storage inventory. Counts are not retained-byte or resident-memory estimates.
 * Does not consolidate reverse operations, promote nodes, decode vectors, or cache decoded data.
 * Requires command/evaluation exclusion, like the evaluator's other graph dumps.
 */
public record GraphStorageStats(
    String mode,
    @Nullable String generation,
    long nodes,
    long doneNodes,
    long dirtyNodes,
    long errorNodes,
    long directDependencies,
    long directGroups,
    long heapDependencyArrays,
    long heapDependencyArrayReferenceSlots,
    long mappedBytes,
    long materializedDependencies,
    long promotedDependencies,
    long copiedMappedBytes,
    ImmutableMap<String, Long> promotionsByReason,
    ImmutableMap<String, Long> valueNodeCountsByClass) {

  public static GraphStorageStats collect(InMemoryGraph graph) throws InterruptedException {
    long nodes = 0;
    long done = 0;
    long dirty = 0;
    long errors = 0;
    long dependencies = 0;
    long groups = 0;
    long arrays = 0;
    long slots = 0;
    var types = new TreeMap<String, Long>();
    for (var entry : graph.getAllNodeEntries()) {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      nodes++;
      if (entry.isDone()) {
        done++;
        if (entry.getErrorInfo() != null) {
          errors++;
        }
        if (entry.keepsEdges()) {
          DoneDeps deps = entry.getDoneDeps();
          dependencies += deps.dependencyCount();
          groups += deps.groupCount();
          if (deps instanceof HeapDoneDeps heap && heap.arrayReferenceSlots() != 0) {
            arrays++;
            slots += heap.arrayReferenceSlots();
          }
        }
      } else {
        dirty++;
      }
      SkyValue value = entry.toValue();
      if (value != null) {
        types.merge(value.getClass().getName(), 1L, Long::sum);
      }
    }
    if (graph instanceof LayeredInMemoryGraph layered) {
      var generation = layered.baselineGeneration();
      return new GraphStorageStats(
          layered.mappedBytes() == 0 ? "layered_heap" : "mapped",
          generation == null ? null : generation.toString(),
          nodes,
          done,
          dirty,
          errors,
          dependencies,
          groups,
          arrays,
          slots,
          layered.mappedBytes(),
          layered.materializedDependencyCount(),
          layered.copiedDependencyCount(),
          layered.copiedMappedBytes(),
          ImmutableMap.copyOf(new TreeMap<>(layered.promotionsByReason())),
          ImmutableMap.copyOf(types));
    }
    return new GraphStorageStats(
        "native",
        null,
        nodes,
        done,
        dirty,
        errors,
        dependencies,
        groups,
        arrays,
        slots,
        0,
        0,
        0,
        0,
        ImmutableMap.of(),
        ImmutableMap.copyOf(types));
  }
}
