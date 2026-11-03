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
import static com.google.devtools.build.skyframe.GraphTester.nonHermeticKey;
import static com.google.devtools.build.skyframe.GraphTester.skyKey;

import com.google.devtools.build.skyframe.GraphTester.StringValue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Runs the evaluator semantics with a frozen baseline after its first successful evaluation. */
@RunWith(JUnit4.class)
public final class LayeredMemoizingEvaluatorTest extends InMemoryMemoizingEvaluatorTest {
  private LayeredInMemoryGraph layeredGraph;

  @Override
  protected InMemoryGraphFactory graphFactory() {
    return (keepEdges, pooledInterning) -> {
      layeredGraph =
          (LayeredInMemoryGraph) LayeredInMemoryGraph.FACTORY.create(keepEdges, pooledInterning);
      return layeredGraph;
    };
  }

  @Override
  protected void afterEvaluation(EvaluationResult<?> result, EvaluationContext context)
      throws InterruptedException {
    if (result != null && !result.hasError() && layeredGraph.baselineGeneration() == null) {
      layeredGraph.freezeAtQuiescentBoundary();
    }
  }

  @Test
  public void equalValueReplacesReverseEdgesAfterFreeze() throws Exception {
    var a = nonHermeticKey("a");
    var b = nonHermeticKey("b");
    var c = nonHermeticKey("c");
    var d = nonHermeticKey("d");
    var top = skyKey("top");
    var computations = new AtomicInteger();
    tester.set(b, new StringValue("b"));
    tester.set(c, new StringValue("c"));
    tester.set(d, new StringValue("d"));
    tester
        .getOrCreate(a)
        .addDependency(b)
        .addDependency(c)
        .setComputedValue(
            (deps, env) -> {
              computations.incrementAndGet();
              return new StringValue("equal");
            });
    tester.getOrCreate(top).addDependency(a).setComputedValue(GraphTester.COPY);
    tester.eval(false, top, d);
    assertThat(layeredGraph.baselineNodeCount()).isGreaterThan(0);
    assertThat(computations.get()).isEqualTo(1);

    tester.getOrCreate(a, true).removeDependency(c).addDependency(d);
    tester.invalidate();
    assertThat(tester.evalAndGet(false, top)).isEqualTo(new StringValue("equal"));
    assertThat(computations.get()).isEqualTo(2);
    assertThat(layeredGraph.getIfPresent(a).getDirectDeps()).containsExactly(b, d);
    assertThat(layeredGraph.getIfPresent(c).getReverseDepsForDoneEntry()).doesNotContain(a);
    assertThat(layeredGraph.getIfPresent(d).getReverseDepsForDoneEntry()).contains(a);

    tester.set(d, new StringValue("changed d"));
    tester.invalidate();
    tester.eval(false, top);
    assertThat(computations.get()).isEqualTo(3);

    tester.set(c, new StringValue("changed c"));
    tester.invalidate();
    tester.eval(false, top);
    assertThat(computations.get()).isEqualTo(3);
  }
}
