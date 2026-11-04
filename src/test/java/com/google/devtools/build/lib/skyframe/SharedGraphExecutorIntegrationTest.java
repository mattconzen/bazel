// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.common.eventbus.EventBus;
import com.google.devtools.build.lib.actions.FileValue;
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.InMemoryGraphFactory;
import com.google.devtools.build.skyframe.LayeredInMemoryGraph;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Runs real executor reconciliation after ordinary command-local monitors have been drained. */
@RunWith(JUnit4.class)
public final class SharedGraphExecutorIntegrationTest extends BuildViewTestCase {
  @Override
  protected String getSharedGraphMode() {
    return "heap";
  }

  @Override
  protected InMemoryGraphFactory getGraphFactory() {
    return LayeredInMemoryGraph.FACTORY;
  }

  @Test
  public void reconciliationInitializesProgressReceiverAndRetainsEvaluator() throws Exception {
    scratch.file("pkg/data.txt", "old");
    scratch.file("pkg/BUILD", "filegroup(name='files', srcs=glob(['*.txt']))");
    scratch.file("other/BUILD", "filegroup(name='files')");
    update(ImmutableList.of("//pkg:files", "//other:files"), false, 1, true, new EventBus());
    var executor = (SequencedSkyframeExecutor) skyframeExecutor;
    var evaluator = executor.getEvaluator();
    var pending =
        FileValue.key(
            RootedPath.toRootedPath(
                Root.fromPath(rootDirectory), PathFragment.create("other/BUILD")));
    assertThat(evaluator.getInMemoryGraph().getIfPresent(pending)).isNotNull();
    executor.setEventBus(new EventBus());
    executor.drainChangedFiles();
    // Make an unrelated entry already dirty before freezing, using the normal invalidator.
    executor.startIncrementalBuildMonitoring();
    executor.getDifferencerForTesting().invalidate(ImmutableList.of(pending));
    evaluator.evaluate(
        ImmutableList.of(),
        EvaluationContext.newBuilder()
            .setKeepGoing(true)
            .setParallelism(1)
            .setEventHandler(reporter)
            .build());
    var dirtyEntry = evaluator.getInMemoryGraph().getIfPresent(pending);
    assertThat(dirtyEntry.isDirty()).isTrue();
    executor.drainChangedFiles();
    executor.noteSharedGraphCommandResult("build", true);
    var prepared = executor.sharedGraphOperation("prepare", "", "", "", reporter);
    scratch.overwriteFile("pkg/data.txt", "new");
    scratch.file("pkg/added.txt", "added");
    var resumed =
        executor.sharedGraphOperation(
            "reconcile", "", "", (String) prepared.get("generation"), reporter);
    assertThat(resumed.get("state")).isEqualTo("READY");
    assertThat((Integer) resumed.get("changed_nodes")).isGreaterThan(0);
    assertThat(executor.getEvaluator()).isSameInstanceAs(evaluator);
    // Reconciliation must retain unrelated dirty entries rather than deleting them.
    assertThat(evaluator.getInMemoryGraph().getIfPresent(pending)).isSameInstanceAs(dirtyEntry);
    assertThat(evaluator.getInMemoryGraph().getIfPresent(pending).isDirty()).isTrue();
    update(ImmutableList.of("//pkg:files", "//other:files"), false, 1, true, new EventBus());
    assertThat(
            getFilesToBuild(getConfiguredTarget("//pkg:files")).toList().stream()
                .map(a -> a.getFilename())
                .toList())
        .containsExactly("data.txt", "added.txt");
  }
}
