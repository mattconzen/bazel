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
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Contract tests for completed dependency readers. */
@RunWith(TestParameterInjector.class)
public final class DoneDepsTest {
  @TestParameter({"0", "1", "2", "17"})
  private int size;

  @Test
  public void preservesGroupsAndCanonicalKeys() {
    GroupedDeps original = new GroupedDeps();
    ImmutableList.Builder<SkyKey> expected = ImmutableList.builder();
    for (int group = 0; group < size; group++) {
      ImmutableList.Builder<SkyKey> keys = ImmutableList.builder();
      for (int i = 0; i <= group; i++) {
        SkyKey key = GraphTester.skyKey(group + ":" + i);
        keys.add(key);
        expected.add(key);
      }
      original.appendGroup(keys.build());
    }
    DoneDeps reader = new HeapDoneDeps(original.compress());
    assertThat(reader.dependencyCount()).isEqualTo(original.numElements());
    assertThat(reader.groupCount()).isEqualTo(original.numGroups());
    assertThat(reader.groups()).containsExactlyElementsIn(original).inOrder();
    assertThat(reader).containsExactlyElementsIn(expected.build()).inOrder();
    Iterator<SkyKey> actual = reader.iterator();
    for (SkyKey key : expected.build()) {
      assertThat(actual.next()).isSameInstanceAs(key);
    }
    assertThat(actual.hasNext()).isFalse();
    assertThrows(NoSuchElementException.class, actual::next);
    assertThrows(UnsupportedOperationException.class, actual::remove);
  }

  @Test
  public void materializationIsIndependentAndGroupsAreReadOnly() {
    GroupedDeps original = new GroupedDeps();
    original.appendSingleton(GraphTester.skyKey("singleton"));
    original.appendGroup(ImmutableList.of(GraphTester.skyKey("a"), GraphTester.skyKey("b")));
    DoneDeps reader = new HeapDoneDeps(original.compress());
    GroupedDeps mutable = reader.materializeForEvaluation();
    mutable.appendSingleton(GraphTester.skyKey("new"));
    assertThat(reader.dependencyCount()).isEqualTo(3);
    assertThat(reader.groupCount()).isEqualTo(2);
    assertThat(reader.groups()).containsExactlyElementsIn(original).inOrder();
    for (List<SkyKey> group : reader.groups()) {
      assertThrows(
          UnsupportedOperationException.class, () -> group.set(0, GraphTester.skyKey("x")));
      assertThrows(UnsupportedOperationException.class, () -> group.add(GraphTester.skyKey("x")));
    }
    Iterator<List<SkyKey>> first = reader.groups().iterator();
    Iterator<List<SkyKey>> second = reader.groups().iterator();
    assertThat(first.next()).isEqualTo(second.next());
    first.next();
    assertThrows(NoSuchElementException.class, first::next);
    assertThat(second.hasNext()).isTrue();
  }
}
