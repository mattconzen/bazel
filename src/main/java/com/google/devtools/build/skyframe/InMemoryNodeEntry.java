// Copyright 2023 The Bazel Authors. All rights reserved.
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

import java.util.Collection;
import javax.annotation.Nullable;

/**
 * A {@link NodeEntry} that is stored in memory.
 *
 * <p>Supports several {@link NodeEntry} methods without throwing {@link InterruptedException}.
 */
public interface InMemoryNodeEntry extends NodeEntry {

  /** Returns the {@link SkyKey} associated with this node. */
  SkyKey getKey();

  /** Whether this node stores edges (deps and rdeps). */
  boolean keepsEdges();

  /**
   * Returns the compressed {@link GroupedDeps} of direct deps. Can only be called if this node
   * {@link #isDone} and {@link #keepsEdges}.
   */
  @GroupedDeps.Compressed
  Object getCompressedDirectDepsForDoneEntry();

  /** Storage-independent reader of completed direct deps. Requires done state and stored edges. */
  default DoneDeps getDoneDeps() {
    return new HeapDoneDeps(getCompressedDirectDepsForDoneEntry());
  }

  /**
   * Last evaluation version, including evaluations that pruned to an equal value. Non-incremental
   * entries have only their constant version.
   */
  default Version lastEvaluatedVersion() {
    return getVersion();
  }

  /**
   * Returns last-build deps if this node can be kept by change pruning, or null otherwise. A
   * non-null result requires every dependency to be present and no newer than this node's last
   * evaluation version. Implementations without incremental dirty state return null.
   */
  @Nullable
  default Iterable<SkyKey> lastBuildDepsIfChangePrunable() {
    return null;
  }

  /**
   * Discards the value for a serializer that will abandon incremental evaluation. Implementations
   * must release the old storage or explicitly reject this destructive operation.
   */
  default void clearSkyValue() {
    throw new UnsupportedOperationException("Value clearing is unsupported by this node storage");
  }

  /** Discards direct deps. The graph must not be used for incremental builds afterwards. */
  default void clearDirectDeps() {
    throw new UnsupportedOperationException(
        "Dependency clearing is unsupported by this node storage");
  }

  /** Applies pending reverse-edge operations, preserving the existing notification semantics. */
  default void consolidateReverseDeps() {
    throw new UnsupportedOperationException("Reverse edge consolidation is unsupported");
  }

  @Override // Remove InterruptedException.
  @Nullable
  SkyValue getValue();

  @Override // Remove InterruptedException.
  @Nullable
  SkyValue getValueMaybeWithMetadata();

  @Override // Remove InterruptedException.
  @Nullable
  SkyValue toValue();

  @Override // Remove InterruptedException.
  @Nullable
  ErrorInfo getErrorInfo();

  @Override // Remove InterruptedException.
  Iterable<SkyKey> getDirectDeps();

  @Override // Remove InterruptedException.
  boolean hasAtLeastOneDep();

  @Override // Remove InterruptedException.
  void removeReverseDep(SkyKey reverseDep);

  @Override // Remove InterruptedException.
  Collection<SkyKey> getReverseDepsForDoneEntry();

  @Override // Remove InterruptedException.
  DependencyState addReverseDepAndCheckIfDone(@Nullable SkyKey reverseDep);

  @Override // Remove InterruptedException.
  @Nullable
  MarkedDirtyResult markDirty(DirtyType dirtyType);

  /**
   * Atomically checks {@link #getLifecycleState} and marks this node changed if in {@link
   * LifecycleState#CHECK_DEPENDENCIES}.
   *
   * <p>Logically equivalent to the following, except executed atomically to avoid a check-then-act
   * race:
   *
   * <pre>{@code
   * LifecycleState state = entry.getLifecycleState();
   * if (state == LifecycleState.CHECK_DEPENDENCIES) {
   *   entry.markDirty(DirtyType.CHANGE);
   * }
   * return state;
   * }</pre>
   *
   * @return the lifecycle state observed before potentially marking this node changed
   */
  LifecycleState markChangedIfCheckingDependencies();
}
