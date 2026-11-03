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

import java.util.List;

/**
 * Read-only direct dependencies of a completed node, independent of their storage representation.
 *
 * <p>Readers preserve group order and within-group order. Traversal must not reconstruct the
 * compressed dependency array or retain a decoded copy. Iterators are independent and do not
 * support removal. A reader remains valid if its node is subsequently dirtied; it describes the
 * completed state from which it was obtained. Keys retain their ordinary canonical Java identity.
 */
public interface DoneDeps extends Iterable<SkyKey> {
  int dependencyCount();

  int groupCount();

  /** Returns repeatable, read-only group views without materializing the full dependency list. */
  Iterable<List<SkyKey>> groups();

  /** Returns a new mutable dependency list for evaluation. This is a materialization boundary. */
  default GroupedDeps materializeForEvaluation() {
    GroupedDeps result = new GroupedDeps();
    for (List<SkyKey> group : groups()) {
      result.appendGroup(group);
    }
    return result;
  }
}
