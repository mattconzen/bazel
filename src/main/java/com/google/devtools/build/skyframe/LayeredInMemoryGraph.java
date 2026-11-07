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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadHostile;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import javax.annotation.Nullable;

/**
 * Experimental stable-handle graph with a heap-backed completed baseline and private native state.
 *
 * <p>The inherited node map is the authoritative membership index for both representations. A
 * removed baseline node is absent from this index and can never be looked up in the baseline store,
 * even if another entry is later created for the same key. No baseline-key fallback or
 * decoded-entry eviction is permitted. This retains all native live-view and interning behavior.
 *
 * <p>Freezing is an internal lifecycle operation requiring external command/evaluation exclusion.
 * The offline snapshot lifecycle coordinates this boundary with command admission and restore
 * reconciliation; generations remain coupled to the matching warmed JVM snapshot.
 */
public final class LayeredInMemoryGraph extends InMemoryGraphImpl {
  /** Opt-in diagnostic roots. Weak references never keep the storage under audit alive. */
  static final class RetentionAudit {
    private final java.util.Map<String, java.util.List<WeakReference<Object>>> references =
        new java.util.LinkedHashMap<>();

    void track(String kind, Object value) {
      references
          .computeIfAbsent(kind, ignored -> new ArrayList<>())
          .add(new WeakReference<>(value));
    }

    java.util.Map<String, Object> finish() {
      var result = new java.util.LinkedHashMap<String, Object>();
      references.forEach(
          (kind, refs) -> {
            long live = 0;
            var classes = new java.util.TreeMap<String, Long>();
            for (var reference : refs) {
              Object value = reference.get();
              if (value != null) {
                live++;
                classes.merge(value.getClass().getName(), 1L, Long::sum);
              }
            }
            result.put(
                kind,
                java.util.Map.of(
                    "tracked_references", (long) refs.size(),
                    "surviving_references", live,
                    "surviving_classes", classes));
          });
      references.clear();
      return result;
    }
  }

  @Nullable private RetentionAudit retentionAudit;

  /** Call after diagnostic template GC; returned counts contain no original storage references. */
  public java.util.Map<String, Object> finishRetentionAudit() {
    var audit = retentionAudit;
    retentionAudit = null;
    return audit == null ? java.util.Map.of() : audit.finish();
  }

  public static final InMemoryGraphFactory FACTORY =
      (keepEdges, pooledInterning) -> {
        checkArgument(keepEdges, "Layered graphs require retained incremental state");
        return new LayeredInMemoryGraph(pooledInterning);
      };

  static final class PromotionCounters {
    private final ConcurrentHashMap<String, LongAdder> reasons = new ConcurrentHashMap<>();
    private final LongAdder copiedDependencies = new LongAdder();
    private final LongAdder copiedMappedBytes = new LongAdder();

    void record(String reason, long dependencies, long mappedBytes) {
      reasons.computeIfAbsent(reason, unused -> new LongAdder()).increment();
      copiedDependencies.add(dependencies);
      copiedMappedBytes.add(mappedBytes);
    }
  }

  private final PromotionCounters promotions = new PromotionCounters();
  @Nullable private volatile FrozenGraphStore baseline;
  private boolean hasFrozen;

  public LayeredInMemoryGraph(boolean usePooledInterning) {
    super(usePooledInterning);
  }

  @Override
  protected InMemoryNodeEntry newNodeEntry(SkyKey key) {
    return new LayeredNodeEntry(key, promotions);
  }

  /**
   * Freezes successful completed nodes once, leaving dirty and error nodes mutable. The caller must
   * exclude all graph-consuming commands, evaluation, cleanup and background mutations. Any failure
   * before publication leaves the graph usable. Interruption is checked before committing, never
   * halfway through publication.
   */
  @ThreadHostile
  public UUID freezeAtQuiescentBoundary() throws InterruptedException {
    return freezeAtQuiescentBoundary(false);
  }

  @ThreadHostile
  public UUID freezeAtQuiescentBoundary(boolean verifyRetention) throws InterruptedException {
    try {
      return freezeAtQuiescentBoundary(null, null, verifyRetention);
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /** Same exclusion contract as heap freezing, with dependency vectors in an immutable arena. */
  @ThreadHostile
  public UUID freezeMappedAtQuiescentBoundary(Path directory, String bazelRevision)
      throws IOException, InterruptedException {
    return freezeMappedAtQuiescentBoundary(directory, bazelRevision, false);
  }

  @ThreadHostile
  public UUID freezeMappedAtQuiescentBoundary(
      Path directory, String bazelRevision, boolean verifyRetention)
      throws IOException, InterruptedException {
    return freezeAtQuiescentBoundary(directory, bazelRevision, verifyRetention);
  }

  private UUID freezeAtQuiescentBoundary(
      @Nullable Path directory, @Nullable String bazelRevision, boolean verifyRetention)
      throws IOException, InterruptedException {
    return freezeAtQuiescentBoundary(directory, bazelRevision, verifyRetention, null);
  }

  @VisibleForTesting
  UUID freezeAtQuiescentBoundary(
      @Nullable Path directory,
      @Nullable String bazelRevision,
      boolean verifyRetention,
      @Nullable Runnable afterHandleCommit)
      throws IOException, InterruptedException {
    checkState(!hasFrozen, "One frozen generation is supported per graph lifetime");
    var audit = verifyRetention ? new RetentionAudit() : null;
    var candidates = new ArrayList<LayeredNodeEntry.FreezeCandidate>();
    for (var node : nodeMap.values()) {
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      var candidate = ((LayeredNodeEntry) node).stageFreeze();
      if (candidate != null) {
        if (audit != null) {
          ((LayeredNodeEntry) node).trackFreezeRetention(audit);
          audit.track("direct_reader", candidate.record().directDeps());
        }
        candidates.add(candidate);
      }
    }
    var replacements = new ArrayList<Object>(candidates.size());
    FrozenGraphStore store =
        directory == null
            ? new FrozenGraphStore(candidates.size())
            : MappedGraphStore.create(directory, bazelRevision, candidates);
    int committed = 0;
    try {
      for (int i = 0; i < candidates.size(); i++) {
        var candidate = candidates.get(i);
        replacements.add(
            store instanceof MappedGraphStore mapped
                ? candidate.handle().mappedFrozenStorage(mapped, i, candidate.record())
                : candidate.handle().frozenStorage(store, candidate.record()));
      }
      if (Thread.interrupted()) {
        throw new InterruptedException();
      }
      for (int i = 0; i < candidates.size(); i++) {
        var candidate = candidates.get(i);
        candidate.handle().installStorage(candidate.original(), replacements.get(i));
        committed++;
        if (afterHandleCommit != null) {
          afterHandleCommit.run();
        }
      }
    } catch (InterruptedException | RuntimeException | Error failure) {
      for (int i = committed - 1; i >= 0; i--) {
        var candidate = candidates.get(i);
        candidate.handle().installStorage(replacements.get(i), candidate.original());
      }
      if (store.backingFile() != null) {
        try {
          Files.deleteIfExists(store.backingFile());
        } catch (IOException cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
      throw failure;
    }
    baseline = store;
    hasFrozen = true;
    retentionAudit = audit;
    return store.generation();
  }

  @Nullable
  public UUID baselineGeneration() {
    var store = baseline;
    return store == null ? null : store.generation();
  }

  public void verifyBaseline() throws IOException {
    if (baseline instanceof MappedGraphStore mapped) {
      mapped.verifyBackingFile();
    }
  }

  public void prefaultBaseline() {
    if (baseline instanceof MappedGraphStore mapped) {
      mapped.prefault();
    }
  }

  /** Caller must hold PREPARED command exclusion and a read-only filesystem proof. */
  public void rebindMappedBaseline(Path file) throws IOException {
    checkState(baseline instanceof MappedGraphStore, "Mapped baseline required for arena rebind");
    ((MappedGraphStore) baseline).rebind(file);
  }

  public long mappedBytes() {
    var store = baseline;
    return store == null ? 0 : store.mappedBytes();
  }

  @Nullable
  public Path backingFile() {
    var store = baseline;
    return store == null ? null : store.backingFile();
  }

  public long materializedDependencyCount() {
    return baseline instanceof MappedGraphStore mapped ? mapped.materializedDependencyCount() : 0;
  }

  FrozenGraphStore baselineStoreForTesting() {
    return baseline;
  }

  public int baselineNodeCount() {
    var store = baseline;
    return store == null ? 0 : store.size();
  }

  public ImmutableMap<String, Long> promotionsByReason() {
    var result = ImmutableMap.<String, Long>builder();
    promotions.reasons.forEach((reason, count) -> result.put(reason, count.sum()));
    return result.buildOrThrow();
  }

  /** Encoded arena bytes materialized on promotion, excluding retained Java values and keys. */
  public long copiedMappedBytes() {
    return promotions.copiedMappedBytes.sum();
  }

  public long copiedDependencyCount() {
    return promotions.copiedDependencies.sum();
  }

  @Override
  public void cleanupInterningPools() {
    super.cleanupInterningPools();
    nodeMap.clear();
    baseline = null;
  }
}
