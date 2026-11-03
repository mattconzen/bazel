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

/** Generation lifetime metadata for snapshot-coupled completed records. Not a portable cache. */
class FrozenGraphStore {
  private final UUID generation;
  private final int size;

  FrozenGraphStore(int size) {
    this(size, UUID.randomUUID());
  }

  FrozenGraphStore(int size, UUID generation) {
    this.size = size;
    this.generation = generation;
  }

  long mappedBytes() {
    return 0;
  }

  java.nio.file.Path backingFile() {
    return null;
  }

  UUID generation() {
    return generation;
  }

  int size() {
    return size;
  }
}
