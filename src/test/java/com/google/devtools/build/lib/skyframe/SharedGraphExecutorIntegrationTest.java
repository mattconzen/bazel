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
import com.google.devtools.build.lib.vfs.FileSystemUtils;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.Root;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.EvaluationContext;
import com.google.devtools.build.skyframe.InMemoryGraphFactory;
import com.google.devtools.build.skyframe.LayeredInMemoryGraph;
import com.google.testing.junit.testparameterinjector.TestParameter;
import com.google.testing.junit.testparameterinjector.TestParameterInjector;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** Runs real executor reconciliation after ordinary command-local monitors have been drained. */
@RunWith(TestParameterInjector.class)
public final class SharedGraphExecutorIntegrationTest extends BuildViewTestCase {
  @TestParameter({"heap", "mapped"})
  private String mode;

  @Rule public final TemporaryFolder arena = new TemporaryFolder();

  @Override
  protected String getSharedGraphMode() {
    return mode;
  }

  @Override
  protected InMemoryGraphFactory getGraphFactory() {
    return LayeredInMemoryGraph.FACTORY;
  }

  @Test
  public void verifiedUnchangedCloneRetainsEvaluatorAndLaterOrdinaryEditsInvalidate()
      throws Exception {
    scratch.file("pkg/data.txt", "old");
    scratch.file("pkg/BUILD", "filegroup(name='files', srcs=glob(['*.txt']))");
    update(ImmutableList.of("//pkg:files"), false, 1, true, new EventBus());
    var executor = (SequencedSkyframeExecutor) skyframeExecutor;
    var evaluator = executor.getEvaluator();
    executor.setEventBus(new EventBus());
    executor.drainChangedFiles();
    executor.noteSharedGraphCommandResult("build", true);
    var prepared =
        executor.sharedGraphOperation(
            "prepare",
            mode.equals("mapped") ? arena.newFolder().getAbsolutePath() : "",
            "0123456789abcdef0123456789abcdef01234567",
            "",
            reporter,
            false,
            ImmutableList.of(),
            true);
    var resumed =
        executor.sharedGraphOperation(
            "reconcile",
            "",
            "",
            (String) prepared.get("generation"),
            reporter,
            false,
            ImmutableList.of(),
            true);
    assertThat(resumed.get("state")).isEqualTo("READY");
    assertThat(resumed.get("changed_nodes")).isEqualTo(0);
    assertThat(resumed.get("reconciliation_change_evidence"))
        .isEqualTo("controller-verified-unchanged-filesystem-clone");
    assertThat(executor.getEvaluator()).isSameInstanceAs(evaluator);
    scratch.overwriteFile("pkg/data.txt", "new");
    scratch.file("pkg/added.txt", "added");
    // BuildViewTestCase.update invokes analysis directly, not the ordinary
    // command's filesystem-diff phase. Supply its standard invalidation hook;
    // these edits do not change configuration inputs.
    invalidatePackages(/* alsoConfigs= */ false);
    update(ImmutableList.of("//pkg:files"), false, 1, true, new EventBus());
    assertThat(executor.getEvaluator()).isSameInstanceAs(evaluator);
    assertThat(
            getFilesToBuild(getConfiguredTarget("//pkg:files")).toList().stream()
                .map(a -> a.getFilename())
                .toList())
        .containsExactly("data.txt", "added.txt");
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
    var prepared =
        executor.sharedGraphOperation(
            "prepare",
            mode.equals("mapped") ? arena.newFolder().getAbsolutePath() : "",
            "0123456789abcdef0123456789abcdef01234567",
            "",
            reporter);
    var preparedStatus = executor.sharedGraphOperation("status", "", "", "", reporter);
    assertThat(preparedStatus.get("state")).isEqualTo("PREPARED");
    assertThat(preparedStatus.get("nodes")).isEqualTo(prepared.get("nodes"));
    assertThat(preparedStatus.get("promoted_dependency_ids"))
        .isEqualTo(prepared.get("promoted_dependency_ids"));
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

  @Test
  public void configurationChangeAfterRestoreReanalyzesConfiguredTargets() throws Exception {
    scratch.file("pkg/old.txt", "old");
    scratch.file("pkg/new.txt", "new");
    scratch.file(
        "pkg/BUILD",
        "config_setting(name='new', values={'define': 'variant=new'})",
        "filegroup(name='files', srcs=select({",
        "    ':new': ['new.txt'],",
        "    '//conditions:default': ['old.txt'],",
        "}))");
    useConfiguration("--define=variant=old");
    update(ImmutableList.of("//pkg:files"), false, 1, true, new EventBus());
    assertThat(
            getFilesToBuild(getConfiguredTarget("//pkg:files")).toList().stream()
                .map(a -> a.getFilename())
                .toList())
        .containsExactly("old.txt");
    var executor = (SequencedSkyframeExecutor) skyframeExecutor;
    var evaluator = executor.getEvaluator();
    executor.setEventBus(new EventBus());
    executor.drainChangedFiles();
    executor.noteSharedGraphCommandResult("build", true);
    var prepared =
        executor.sharedGraphOperation(
            "prepare",
            mode.equals("mapped") ? arena.newFolder().getAbsolutePath() : "",
            "0123456789abcdef0123456789abcdef01234567",
            "",
            reporter);
    executor.sharedGraphOperation(
        "reconcile", "", "", (String) prepared.get("generation"), reporter);
    useConfiguration("--define=variant=new");
    update(ImmutableList.of("//pkg:files"), false, 1, true, new EventBus());
    assertThat(executor.getEvaluator()).isSameInstanceAs(evaluator);
    assertThat(
            getFilesToBuild(getConfiguredTarget("//pkg:files")).toList().stream()
                .map(a -> a.getFilename())
                .toList())
        .containsExactly("new.txt");
  }

  @Test
  public void reconciliationDetectsStarlarkEditWithPreservedSizeAndMtime() throws Exception {
    scratch.file("pkg/old.txt", "old");
    scratch.file("pkg/new.txt", "new");
    var definitions = scratch.file("pkg/defs.bzl", "FILES = ['old.txt']");
    scratch.file("pkg/BUILD", "load(':defs.bzl', 'FILES')", "filegroup(name='files', srcs=FILES)");
    update(ImmutableList.of("//pkg:files"), false, 1, true, new EventBus());
    var executor = (SequencedSkyframeExecutor) skyframeExecutor;
    var evaluator = executor.getEvaluator();
    String generation = prepareForContentRevalidation(executor);
    var before = definitions.stat(Symlinks.NOFOLLOW);
    // Overwrite the inode instead of replacing the file, then restore its saved mtime.
    FileSystemUtils.writeContentAsLatin1(definitions, "FILES = ['new.txt']\n");
    definitions.setLastModifiedTime(before.getLastModifiedTime());
    var after = definitions.stat(Symlinks.NOFOLLOW);
    assertThat(after.getNodeId()).isEqualTo(before.getNodeId());
    assertThat(after.getSize()).isEqualTo(before.getSize());
    assertThat(after.getLastModifiedTime()).isEqualTo(before.getLastModifiedTime());

    reconcileAndAssertFiles(executor, generation, "new.txt");
    assertThat(executor.getEvaluator()).isSameInstanceAs(evaluator);
  }

  @Test
  public void reconciliationDetectsRetargetedStarlarkSymlink() throws Exception {
    scratch.file("pkg/old.txt", "old");
    scratch.file("pkg/new.txt", "new");
    var oldDefinitions = scratch.file("pkg/old.bzl", "FILES = ['old.txt']");
    var newDefinitions = scratch.file("pkg/new.bzl", "FILES = ['new.txt']");
    var link = oldDefinitions.getParentDirectory().getChild("defs.bzl");
    link.createSymbolicLink(oldDefinitions);
    scratch.file("pkg/BUILD", "load(':defs.bzl', 'FILES')", "filegroup(name='files', srcs=FILES)");
    update(ImmutableList.of("//pkg:files"), false, 1, true, new EventBus());
    var executor = (SequencedSkyframeExecutor) skyframeExecutor;
    String generation = prepareForContentRevalidation(executor);
    link.delete();
    link.createSymbolicLink(newDefinitions);

    reconcileAndAssertFiles(executor, generation, "new.txt");
  }

  @Test
  public void reconciliationDetectsGlobRemovalAndNewSubdirectory() throws Exception {
    var removed = scratch.file("pkg/old.txt", "old");
    scratch.file("pkg/BUILD", "filegroup(name='files', srcs=glob(['**/*.txt']))");
    update(ImmutableList.of("//pkg:files"), false, 1, true, new EventBus());
    var executor = (SequencedSkyframeExecutor) skyframeExecutor;
    String generation = prepareForContentRevalidation(executor);
    removed.delete();
    scratch.file("pkg/nested/new.txt", "new");

    reconcileAndAssertFiles(executor, generation, "new.txt");
  }

  private String prepareForContentRevalidation(SequencedSkyframeExecutor executor)
      throws Exception {
    executor.setEventBus(new EventBus());
    executor.drainChangedFiles();
    executor.noteSharedGraphCommandResult("build", true);
    return (String)
        executor
            .sharedGraphOperation(
                "prepare",
                mode.equals("mapped") ? arena.newFolder().getAbsolutePath() : "",
                "0123456789abcdef0123456789abcdef01234567",
                "",
                reporter)
            .get("generation");
  }

  private void reconcileAndAssertFiles(
      SequencedSkyframeExecutor executor, String generation, String... filenames) throws Exception {
    var resumed = executor.sharedGraphOperation("reconcile", "", "", generation, reporter);
    assertThat(resumed.get("state")).isEqualTo("READY");
    assertThat((Integer) resumed.get("changed_nodes")).isGreaterThan(0);
    // Do not inject package invalidations: reconciliation must discover the changes itself.
    update(ImmutableList.of("//pkg:files"), false, 1, true, new EventBus());
    assertThat(
            getFilesToBuild(getConfiguredTarget("//pkg:files")).toList().stream()
                .map(a -> a.getFilename())
                .toList())
        .containsExactlyElementsIn(filenames);
  }
}
