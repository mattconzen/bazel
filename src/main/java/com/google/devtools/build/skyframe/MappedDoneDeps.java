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

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** Direct dependency cursors over validated integer vectors, with no decoded array cache. */
final class MappedDoneDeps implements DoneDeps {
  private final MappedGraphStore store;
  private final long offset;
  private final int groups;
  private final int dependencies;

  MappedDoneDeps(MappedGraphStore store, long offset) {
    this.store = store;
    this.offset = offset;
    this.groups = store.getInt(offset);
    this.dependencies = store.getInt(offset + 4);
  }

  private long keysOffset() {
    return offset + 8 + 4L * groups;
  }

  long encodedBytes() {
    return 8 + 4L * ((long) groups + dependencies);
  }

  @Override
  public int dependencyCount() {
    return dependencies;
  }

  @Override
  public int groupCount() {
    return groups;
  }

  @Override
  public Iterator<SkyKey> iterator() {
    return new Iterator<>() {
      private int position;

      @Override
      public boolean hasNext() {
        return position < dependencies;
      }

      @Override
      public SkyKey next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return store.key(store.getInt(keysOffset() + 4L * position++));
      }
    };
  }

  @Override
  public Iterable<List<SkyKey>> groups() {
    return () ->
        new Iterator<>() {
          private int group;
          private int position;

          @Override
          public boolean hasNext() {
            return group < groups;
          }

          @Override
          public List<SkyKey> next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            int count = store.getInt(offset + 8 + 4L * group++);
            var result = new MappedGraphStore.KeyList(store, keysOffset() + 4L * position, count);
            position += count;
            return result;
          }
        };
  }

  @Override
  public GroupedDeps materializeForEvaluation() {
    store.recordMaterialization(dependencies);
    return DoneDeps.super.materializeForEvaluation();
  }
}
