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
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.skyframe.QueryableGraph.Reason;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests graph selection independently of the evaluator's graph-transformer test hook. */
@RunWith(TestParameterInjector.class)
public final class InMemoryGraphFactoryTest {
  @TestParameter private boolean keepEdges;
  @TestParameter private boolean usePooledInterning;

  @Test
  public void evaluatorForwardsOptionsAndUsesFactoryGraph() throws Exception {
    InMemoryGraph graph = InMemoryGraphFactory.DEFAULT.create(keepEdges, usePooledInterning);
    AtomicInteger calls = new AtomicInteger();
    try {
      InMemoryMemoizingEvaluator evaluator =
          createEvaluator(
              (actualKeepEdges, actualPooledInterning) -> {
                assertThat(actualKeepEdges).isEqualTo(keepEdges);
                assertThat(actualPooledInterning).isEqualTo(usePooledInterning);
                calls.incrementAndGet();
                return graph;
              });
      assertThat(evaluator.getInMemoryGraph()).isSameInstanceAs(graph);
      assertThat(calls.get()).isEqualTo(1);
      SkyKey key = GraphTester.skyKey("key");
      graph.createIfAbsentBatch(null, Reason.OTHER, ImmutableList.of(key));
      assertThat(graph.getIfPresent(key).keepsEdges()).isEqualTo(keepEdges);
    } finally {
      graph.cleanupInterningPools();
    }
  }

  @Test
  public void rejectsNullGraph() {
    assertThrows(NullPointerException.class, () -> createEvaluator((edges, pooled) -> null));
  }

  private InMemoryMemoizingEvaluator createEvaluator(InMemoryGraphFactory factory) {
    return new InMemoryMemoizingEvaluator(
        ImmutableMap.of(),
        new SequencedRecordingDifferencer(),
        EvaluationProgressReceiver.NULL,
        GraphInconsistencyReceiver.THROWING,
        EventFilter.FULL_STORAGE,
        new EmittedEventState(),
        keepEdges,
        usePooledInterning,
        factory);
  }
}
