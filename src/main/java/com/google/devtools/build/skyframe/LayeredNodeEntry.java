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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Stable graph identity. Every transition and operation uses this handle's monitor; mutable
 * delegates never escape. A frozen entry promotes at most once and then uses the native state
 * machine for the rest of its lifetime.
 */
final class LayeredNodeEntry implements InMemoryNodeEntry {
  private sealed interface Storage {}

  private record Mutable(IncrementalInMemoryNodeEntry entry) implements Storage {}

  private record Frozen(FrozenGraphStore owner, FrozenNodeRecord record) implements Storage {}

  private final SkyKey key;
  private final LayeredInMemoryGraph.PromotionCounters counters;
  private volatile Storage storage;

  LayeredNodeEntry(SkyKey key, LayeredInMemoryGraph.PromotionCounters counters) {
    this.key = key;
    this.counters = counters;
    this.storage = new Mutable(new IncrementalInMemoryNodeEntry(key));
  }

  record FreezeCandidate(LayeredNodeEntry handle, Object original, FrozenNodeRecord record) {}

  /**
   * Requires graph-wide command/evaluation exclusion. Consolidates native pending reverse edges.
   */
  synchronized FreezeCandidate stageFreeze() {
    if (!(storage instanceof Mutable mutable)) {
      return null;
    }
    var entry = mutable.entry();
    if (!entry.isDone()
        || entry.getErrorInfo() != null
        || entry.getValueMaybeWithMetadata() == IncrementalInMemoryNodeEntry.CLEARED_SKY_VALUE) {
      return null;
    }
    checkState(
        entry.getMaxTransitiveSourceVersion() == null,
        "Source-version tracking needs a completed-state adapter");
    return new FreezeCandidate(
        this,
        storage,
        new FrozenNodeRecord(
            entry.getValueMaybeWithMetadata(),
            entry.version,
            entry.getDoneDeps(),
            ImmutableList.copyOf(entry.getReverseDepsForDoneEntry())));
  }

  synchronized Object frozenStorage(FrozenGraphStore store, FrozenNodeRecord record) {
    return new Frozen(store, record);
  }

  synchronized void installStorage(Object expected, Object replacement) {
    checkState(storage == expected, "Node changed during freeze: %s", key);
    storage = (Storage) replacement;
  }

  synchronized boolean isFrozen() {
    return storage instanceof Frozen;
  }

  private IncrementalInMemoryNodeEntry mutable(String reason) {
    if (storage instanceof Mutable mutable) {
      return mutable.entry();
    }
    FrozenNodeRecord record = ((Frozen) storage).record();
    var restored = IncrementalInMemoryNodeEntry.restoreCompleted(key, record);
    // Construct the complete delegate before publishing. Failure leaves frozen state intact.
    storage = new Mutable(restored);
    counters.record(
        reason,
        record.directDeps().dependencyCount() + (long) record.reverseDeps().size(),
        record.directDeps() instanceof MappedDoneDeps mapped
            ? mapped.encodedBytes() + 4 + 4L * record.reverseDeps().size()
            : 0);
    return restored;
  }

  @Override
  public SkyKey getKey() {
    return key;
  }

  @Override
  public boolean keepsEdges() {
    return true;
  }

  @Override
  public synchronized boolean isDone() {
    if (storage instanceof Frozen frozen) {
      return true;
    }
    return ((Mutable) storage).entry().isDone();
  }

  @Override
  public synchronized boolean isDirty() {
    if (storage instanceof Frozen frozen) {
      return false;
    }
    return ((Mutable) storage).entry().isDirty();
  }

  @Override
  public synchronized boolean isChanged() {
    if (storage instanceof Frozen frozen) {
      return false;
    }
    return ((Mutable) storage).entry().isChanged();
  }

  @Override
  @Nullable
  public synchronized SkyValue getValue() {
    if (storage instanceof Frozen frozen) {
      FrozenNodeRecord record = frozen.record();
      return ValueWithMetadata.justValue(record.wrappedValue());
    }
    return ((Mutable) storage).entry().getValue();
  }

  @Override
  @Nullable
  public synchronized SkyValue getValueMaybeWithMetadata() {
    if (storage instanceof Frozen frozen) {
      FrozenNodeRecord record = frozen.record();
      return record.wrappedValue();
    }
    return ((Mutable) storage).entry().getValueMaybeWithMetadata();
  }

  @Override
  @Nullable
  public synchronized SkyValue toValue() {
    if (storage instanceof Frozen frozen) {
      FrozenNodeRecord record = frozen.record();
      return ValueWithMetadata.justValue(record.wrappedValue());
    }
    return ((Mutable) storage).entry().toValue();
  }

  @Override
  @Nullable
  public synchronized ErrorInfo getErrorInfo() {
    if (storage instanceof Frozen frozen) {
      FrozenNodeRecord record = frozen.record();
      return ValueWithMetadata.getMaybeErrorInfo(record.wrappedValue());
    }
    return ((Mutable) storage).entry().getErrorInfo();
  }

  @Override
  public synchronized DoneDeps getDoneDeps() {
    if (storage instanceof Frozen frozen) {
      FrozenNodeRecord record = frozen.record();
      return record.directDeps();
    }
    return ((Mutable) storage).entry().getDoneDeps();
  }

  @Override
  public synchronized Iterable<SkyKey> getDirectDeps() {
    if (storage instanceof Frozen frozen) {
      FrozenNodeRecord record = frozen.record();
      return record.directDeps();
    }
    return ((Mutable) storage).entry().getDirectDeps();
  }

  @Override
  public synchronized boolean hasAtLeastOneDep() {
    if (storage instanceof Frozen frozen) {
      FrozenNodeRecord record = frozen.record();
      return record.directDeps().dependencyCount() != 0;
    }
    return ((Mutable) storage).entry().hasAtLeastOneDep();
  }

  @Override
  public synchronized Collection<SkyKey> getReverseDepsForDoneEntry() {
    if (storage instanceof Frozen frozen) {
      FrozenNodeRecord record = frozen.record();
      return record.reverseDeps();
    }
    return ((Mutable) storage).entry().getReverseDepsForDoneEntry();
  }

  @Override
  public synchronized Version getVersion() {
    if (storage instanceof Frozen frozen) {
      FrozenNodeRecord record = frozen.record();
      return record.version().lastChanged();
    }
    return ((Mutable) storage).entry().getVersion();
  }

  @Override
  public synchronized Version lastEvaluatedVersion() {
    if (storage instanceof Frozen frozen) {
      FrozenNodeRecord record = frozen.record();
      return record.version().lastEvaluated();
    }
    return ((Mutable) storage).entry().lastEvaluatedVersion();
  }

  @Override
  @Nullable
  public synchronized Version getMaxTransitiveSourceVersion() {
    if (storage instanceof Frozen frozen) {
      return null;
    }
    return ((Mutable) storage).entry().getMaxTransitiveSourceVersion();
  }

  @Override
  public synchronized LifecycleState getLifecycleState() {
    if (storage instanceof Frozen frozen) {
      return LifecycleState.DONE;
    }
    return ((Mutable) storage).entry().getLifecycleState();
  }

  @Override
  @Nullable
  public synchronized Iterable<SkyKey> lastBuildDepsIfChangePrunable() {
    if (storage instanceof Frozen frozen) {
      return null;
    }
    return ((Mutable) storage).entry().lastBuildDepsIfChangePrunable();
  }

  @Override
  public synchronized @GroupedDeps.Compressed Object getCompressedDirectDepsForDoneEntry() {
    if (storage instanceof Frozen frozen) {
      return frozen.record().directDeps().materializeForEvaluation().compress();
    }
    return ((Mutable) storage).entry().getCompressedDirectDepsForDoneEntry();
  }

  @Override
  public synchronized void removeReverseDep(SkyKey reverseDep) {
    mutable("removeReverseDep").removeReverseDep(reverseDep);
  }

  @Override
  public synchronized void removeReverseDepsFromDoneEntryDueToDeletion(Set<SkyKey> deletedKeys) {
    mutable("removeReverseDepsFromDoneEntryDueToDeletion")
        .removeReverseDepsFromDoneEntryDueToDeletion(deletedKeys);
  }

  @Override
  public synchronized DependencyState addReverseDepAndCheckIfDone(@Nullable SkyKey reverseDep) {
    if (storage instanceof Frozen && reverseDep == null) {
      return DependencyState.DONE;
    }
    return mutable("addReverseDepAndCheckIfDone").addReverseDepAndCheckIfDone(reverseDep);
  }

  @Override
  public synchronized DependencyState checkIfDoneForDirtyReverseDep(SkyKey reverseDep)
      throws InterruptedException {
    checkNotNull(reverseDep, this);
    if (storage instanceof Frozen) {
      return DependencyState.DONE;
    }
    return mutable("checkIfDoneForDirtyReverseDep").checkIfDoneForDirtyReverseDep(reverseDep);
  }

  @Override
  public synchronized MarkedDirtyResult markDirty(DirtyType dirtyType) {
    return mutable("markDirty").markDirty(dirtyType);
  }

  @Override
  public synchronized LifecycleState markChangedIfCheckingDependencies() {
    return mutable("markChangedIfCheckingDependencies").markChangedIfCheckingDependencies();
  }

  @Override
  public synchronized Set<SkyKey> getInProgressReverseDeps() {
    return mutable("getInProgressReverseDeps").getInProgressReverseDeps();
  }

  @Override
  public synchronized Collection<SkyKey> getAllReverseDepsForNodeBeingDeleted() {
    if (storage instanceof Frozen frozen) {
      // Frozen reverse edges are already consolidated. The collection owns the mapped store and
      // remains valid after this handle is removed from graph membership.
      return frozen.record().reverseDeps();
    }
    return mutable("getAllReverseDepsForNodeBeingDeleted").getAllReverseDepsForNodeBeingDeleted();
  }

  @Override
  public synchronized Set<SkyKey> setValue(
      SkyValue value, Version graphVersion, @Nullable Version maxTransitiveSourceVersion)
      throws InterruptedException {
    return mutable("setValue").setValue(value, graphVersion, maxTransitiveSourceVersion);
  }

  @Override
  public synchronized void setTemporaryMaxTransitiveSourceVersion(
      @Nullable Version maxTransitiveSourceVersion) {
    mutable("setTemporaryMaxTransitiveSourceVersion")
        .setTemporaryMaxTransitiveSourceVersion(maxTransitiveSourceVersion);
  }

  @Override
  public synchronized boolean signalDep(Version childVersion, @Nullable SkyKey childForDebugging) {
    return mutable("signalDep").signalDep(childVersion, childForDebugging);
  }

  @Override
  public synchronized NodeValueAndRdepsToSignal markClean() throws InterruptedException {
    return mutable("markClean").markClean();
  }

  @Override
  public synchronized void forceRebuild() {
    mutable("forceRebuild").forceRebuild();
  }

  @Override
  public synchronized List<SkyKey> getNextDirtyDirectDeps() throws InterruptedException {
    return mutable("getNextDirtyDirectDeps").getNextDirtyDirectDeps();
  }

  @Override
  public synchronized ImmutableSet<SkyKey> getAllDirectDepsForIncompleteNode()
      throws InterruptedException {
    return mutable("getAllDirectDepsForIncompleteNode").getAllDirectDepsForIncompleteNode();
  }

  @Override
  public synchronized ImmutableSet<SkyKey> getAllRemainingDirtyDirectDeps()
      throws InterruptedException {
    return mutable("getAllRemainingDirtyDirectDeps").getAllRemainingDirtyDirectDeps();
  }

  @Override
  public synchronized void markRebuilding() {
    mutable("markRebuilding").markRebuilding();
  }

  @Override
  public synchronized GroupedDeps getTemporaryDirectDeps() {
    return mutable("getTemporaryDirectDeps").getTemporaryDirectDeps();
  }

  @Override
  public synchronized boolean noDepsLastBuild() {
    return mutable("noDepsLastBuild").noDepsLastBuild();
  }

  @Override
  public synchronized void removeUnfinishedDeps(Set<SkyKey> unfinishedDeps) {
    mutable("removeUnfinishedDeps").removeUnfinishedDeps(unfinishedDeps);
  }

  @Override
  public synchronized void resetEvaluationFromScratch() {
    mutable("resetEvaluationFromScratch").resetEvaluationFromScratch();
  }

  @Override
  public synchronized ImmutableSet<SkyKey> getResetDirectDeps() {
    return mutable("getResetDirectDeps").getResetDirectDeps();
  }

  @Override
  public synchronized void addSingletonTemporaryDirectDep(SkyKey dep) {
    mutable("addSingletonTemporaryDirectDep").addSingletonTemporaryDirectDep(dep);
  }

  @Override
  public synchronized void addTemporaryDirectDepGroup(List<SkyKey> group) {
    mutable("addTemporaryDirectDepGroup").addTemporaryDirectDepGroup(group);
  }

  @Override
  public synchronized void addTemporaryDirectDepsInGroups(
      Set<SkyKey> deps, List<Integer> groupSizes) {
    mutable("addTemporaryDirectDepsInGroups").addTemporaryDirectDepsInGroups(deps, groupSizes);
  }

  @Override
  public synchronized void addExternalDep() {
    mutable("addExternalDep").addExternalDep();
  }

  @Override
  public synchronized boolean isReadyToEvaluate() {
    return mutable("isReadyToEvaluate").isReadyToEvaluate();
  }

  @Override
  public synchronized boolean hasUnsignaledDeps() {
    return mutable("hasUnsignaledDeps").hasUnsignaledDeps();
  }

  @Override
  public synchronized void clearSkyValue() {
    mutable("clearSkyValue").clearSkyValue();
  }

  @Override
  public synchronized void clearDirectDeps() {
    mutable("clearDirectDeps").clearDirectDeps();
  }

  @Override
  public synchronized void consolidateReverseDeps() {
    mutable("consolidateReverseDeps").consolidateReverseDeps();
  }

  @Override
  public synchronized String toString() {
    return "LayeredNodeEntry{" + key + ", " + getLifecycleState() + "}";
  }
}
