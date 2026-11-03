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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Runs the same evaluator, error recovery and edge replacement contracts with mapped vectors. */
@RunWith(JUnit4.class)
public final class MappedMemoizingEvaluatorTest extends LayeredMemoizingEvaluatorTest {
  @Override
  protected void freeze(LayeredInMemoryGraph graph) throws InterruptedException {
    try {
      graph.freezeMappedAtQuiescentBoundary(
          Files.createTempDirectory(Path.of(System.getenv("TEST_TMPDIR")), "arena-"),
          "fda97de2b3b179ecdfa8af455fffde8d0b6b6422");
    } catch (IOException failure) {
      throw new AssertionError(failure);
    }
  }
}
