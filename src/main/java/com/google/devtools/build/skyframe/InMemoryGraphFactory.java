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

/** Creates a new graph for an evaluator, including when the executor resets its evaluator. */
@FunctionalInterface
public interface InMemoryGraphFactory {
  InMemoryGraphFactory DEFAULT =
      (keepEdges, usePooledInterning) ->
          keepEdges
              ? InMemoryGraph.create(usePooledInterning)
              : InMemoryGraph.createEdgeless(usePooledInterning);

  /**
   * Returns a fresh, empty graph honoring both flags. Implementations must reject unsupported
   * combinations rather than silently changing incremental evaluation or canonical key identity.
   */
  InMemoryGraph create(boolean keepEdges, boolean usePooledInterning);
}
