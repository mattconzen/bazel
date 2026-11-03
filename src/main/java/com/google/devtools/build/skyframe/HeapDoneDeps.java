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

import com.google.common.collect.ImmutableList;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** A zero-copy reader over the existing compressed heap representation. */
final class HeapDoneDeps implements DoneDeps {
  private final @GroupedDeps.Compressed Object compressed;

  HeapDoneDeps(@GroupedDeps.Compressed Object compressed) {
    this.compressed = compressed;
  }

  @Override
  public int dependencyCount() {
    return GroupedDeps.numElements(compressed);
  }

  @Override
  public int groupCount() {
    if (!(compressed instanceof Object[] array)) {
      return compressed instanceof SkyKey ? 1 : 0;
    }
    int groups = 0;
    for (int i = 0; i < array.length; groups++) {
      Object element = array[i++];
      if (element instanceof Integer count) {
        i += count;
      }
    }
    return groups;
  }

  @Override
  public Iterator<SkyKey> iterator() {
    if (!(compressed instanceof Object[] array)) {
      return GroupedDeps.compressedToIterable(compressed).iterator();
    }
    return new Iterator<>() {
      private int position;

      @Override
      public boolean hasNext() {
        return position < array.length;
      }

      @Override
      public SkyKey next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        Object element = array[position++];
        return element instanceof SkyKey key ? key : (SkyKey) array[position++];
      }
    };
  }

  @Override
  public Iterable<List<SkyKey>> groups() {
    if (!(compressed instanceof Object[] array)) {
      return compressed instanceof SkyKey key
          ? ImmutableList.of(ImmutableList.of(key))
          : ImmutableList.of();
    }
    return () ->
        new Iterator<>() {
          private int position;

          @Override
          public boolean hasNext() {
            return position < array.length;
          }

          @Override
          public List<SkyKey> next() {
            if (!hasNext()) {
              throw new NoSuchElementException();
            }
            Object element = array[position++];
            if (element instanceof SkyKey key) {
              return ImmutableList.of(key);
            }
            int start = position;
            int count = (Integer) element;
            position += count;
            return new AbstractList<>() {
              @Override
              public SkyKey get(int index) {
                java.util.Objects.checkIndex(index, count);
                return (SkyKey) array[start + index];
              }

              @Override
              public int size() {
                return count;
              }
            };
          }
        };
  }

  @Override
  public GroupedDeps materializeForEvaluation() {
    return GroupedDeps.decompress(compressed);
  }
}
