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

import java.util.UUID;

/** A heap-backed, snapshot-coupled baseline. It is not a portable graph cache. */
final class FrozenGraphStore {
  private final UUID generation = UUID.randomUUID();
  private final int size;

  FrozenGraphStore(int size) {
    this.size = size;
  }

  UUID generation() {
    return generation;
  }

  int size() {
    return size;
  }
}
