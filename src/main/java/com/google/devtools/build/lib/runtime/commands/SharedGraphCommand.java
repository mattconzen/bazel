// Copyright 2015 The Bazel Authors. All rights reserved.
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

package com.google.devtools.build.lib.runtime.commands;

import static com.google.devtools.build.lib.runtime.Command.BuildPhase.NONE;

import com.google.devtools.build.lib.runtime.BlazeCommand;
import com.google.devtools.build.lib.runtime.BlazeCommandResult;
import com.google.devtools.build.lib.runtime.Command;
import com.google.devtools.build.lib.runtime.CommandEnvironment;
import com.google.devtools.build.lib.server.FailureDetails;
import com.google.devtools.build.lib.server.FailureDetails.FailureDetail;
import com.google.devtools.build.lib.skyframe.SequencedSkyframeExecutor;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionDocumentationCategory;
import com.google.devtools.common.options.OptionEffectTag;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsClass;
import com.google.devtools.common.options.OptionsParsingResult;
import java.io.IOException;
import java.util.List;

/** Internal experimental whole-VM lifecycle operations, serialized by the ordinary command lock. */
@Command(
    name = "shared_graph",
    buildPhase = NONE,
    allowResidue = true,
    options = {SharedGraphCommand.Options.class},
    help = "Usage: %{product} shared_graph prepare|reconcile|status|discard\n%{options}",
    shortDescription = "Coordinates experimental snapshot-coupled graph state.")
public final class SharedGraphCommand implements BlazeCommand {
  @OptionsClass
  public abstract static class Options extends OptionsBase {
    @Option(
        name = "shared_graph_directory",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
        help = "Absolute directory for an immutable mapped generation.")
    public abstract String getDirectory();

    @Option(
        name = "shared_graph_revision",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
        help = "Exact source revision of the binary being prepared.")
    public abstract String getRevision();

    @Option(
        name = "shared_graph_generation",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
        help = "Expected prepared generation, required for reconciliation.")
    public abstract String getGeneration();

    @Option(
        name = "shared_graph_verify_retention",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
        help =
            "Diagnostic weak-reference audit of replaced storage after template GC; perturbs"
                + " sharing.")
    public abstract boolean getVerifyRetention();

    @Option(
        name = "shared_graph_immutable_root",
        defaultValue = "null",
        allowMultiple = true,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
        help =
            "Preparation only: absolute input root backed by a controller-attested immutable image."
                + " Requires a read-only filesystem at capture and restore; mutable inputs retain"
                + " full content checks. Repeat for multiple roots.")
    public abstract List<String> getImmutableRoots();

    @Option(
        name = "shared_graph_verified_clean_clone",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = {OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION},
        help =
            "Trusted controller only: at preparation permit unchanged-filesystem clone assertions;"
                + " at reconciliation assert an exact, unmodified clone paired with this JVM"
                + " generation, with all input/output writers excluded. Never set for edited or"
                + " unproven clones. Otherwise mutable content is fully revalidated.")
    public abstract boolean getVerifiedCleanClone();
  }

  @Override
  public BlazeCommandResult exec(CommandEnvironment env, OptionsParsingResult options) {
    try {
      if (options.getResidue().size() != 1) {
        throw new IllegalArgumentException("Expected prepare, reconcile, status, or discard");
      }
      var settings = options.getOptions(Options.class);
      var executor = (SequencedSkyframeExecutor) env.getSkyframeExecutor();
      var status =
          executor.sharedGraphOperation(
              options.getResidue().getFirst(),
              settings.getDirectory(),
              settings.getRevision(),
              settings.getGeneration(),
              env.getReporter(),
              settings.getVerifyRetention(),
              settings.getImmutableRoots(),
              settings.getVerifiedCleanClone());
      env.getReporter()
          .getOutErr()
          .printOutLn(new com.google.gson.GsonBuilder().serializeNulls().create().toJson(status));
      return BlazeCommandResult.success();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return BlazeCommandResult.failureDetail(
          FailureDetail.newBuilder()
              .setMessage("Shared graph operation interrupted")
              .setInterrupted(
                  FailureDetails.Interrupted.newBuilder()
                      .setCode(FailureDetails.Interrupted.Code.INTERRUPTED))
              .build());
    } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
      env.getReporter().error(null, failure.getMessage());
      return BlazeCommandResult.failureDetail(
          FailureDetail.newBuilder()
              .setMessage(failure.getMessage())
              .setCommand(
                  FailureDetails.Command.newBuilder()
                      .setCode(FailureDetails.Command.Code.OPTIONS_PARSE_FAILURE))
              .build());
    }
  }
}
