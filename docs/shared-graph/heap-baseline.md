# Heap-backed frozen graph

`LayeredInMemoryGraph.FACTORY` is an internal experimental backend. It requires
incremental edge tracking; the normal executor still selects the native graph.
There is no startup flag, template command, or automatic restore reconciliation
in this milestone.

Every graph entry is a stable `LayeredNodeEntry`. While mutable it owns an
ordinary `IncrementalInMemoryNodeEntry`; while frozen it owns a completed record.
All operations and representation changes use the handle's monitor. Callers
never receive a delegate. The first potentially mutating operation restores an
entire native delegate before publishing it and then performs the original
operation. Reverse-edge registration, consolidation and removal count as writes.
Promoted nodes stay mutable. The original evaluator and reverse-edge state
machine remain responsible for dirtying, pruning, scheduling and signaling.

`freezeAtQuiescentBoundary` is a caller-excluded lifecycle API: the caller must
stop all graph-consuming commands, evaluation, cleanup and background mutations.
It is not safe to invoke concurrently with those operations. It stages all
successful completed nodes, consolidates reverse edges, checks interruption,
allocates all replacement representations, and then commits. Original delegates
remain available for rollback until all handles commit. Dirty, evaluating,
error and serializer-cleared nodes remain mutable. Source versions are not
tracked by the native incremental node at this revision; a future implementation
that adds such tracking must add an adapter before freezing those entries.

The completed-state factory preserves wrapped values/events, both versions,
grouped direct deps and consolidated reverse deps. Versions are published before
values, following the native node contract. Values remain Java objects, including
identity-sensitive values. There is one generation per graph lifetime.

## Membership and lifetime

The inherited node map is the authoritative membership index. It contains both
frozen and mutable handles, so native live views, traversal, counts, batch
creation and canonical interning continue to operate over the entire graph.
There is no separate baseline key lookup. Removing an entry removes membership;
its baseline record cannot become visible again. A subsequent create returns a
fresh handle. There is no decoded-entry eviction to invalidate this invariant.
A future backend that adds baseline lookup or eviction must introduce explicit
tombstones before doing so.

The store owns generation metadata, not a second strong-reference collection of
all records. Records belong to their handles. This is essential: retaining every
record in a store kept deleted or promoted values alive and failed the existing
`deleteInvalidatedValue` GC regression. Removing or promoting a handle now allows
obsolete heap payloads to be collected through the ordinary evaluator lifetime.
Cleanup retires the generation and index; caller-retained readers remain valid.

This stage intentionally retains dependency arrays on the heap. It establishes
semantics and does not claim lower memory use. Promotion counters report reasons
and copied dependency counts; these counts are not retained-byte estimates.

## Validation

The existing incremental node, graph and evaluator contract classes run against
stable handles. The layered evaluator test freezes at the first successful
evaluation and runs subsequent edits, errors, cycles, cancellation, deletion,
pruning and rewinding through promotion. The seeded evaluator harness compares
both native and layered backends to independently computed values across 100
invalidation rounds, including periodic deletions.

Additional tests cover stable identity and live views across freeze/promotion,
interrupted preparation and retry, dirty/inflight nodes remaining mutable,
delete/recreate and batch recreation without stale data, group/ordering and
last-changed/last-evaluated version preservation, consolidated reverse edges,
and concurrent promotion with exactly one scheduling response. An explicit
A(B,C) -> A(B,D) regression returns an equal value, then verifies D invalidates A
and C no longer does.

Run the aggregate suite and seeded harness:

```sh
bazel test //src/test/java/com/google/devtools/build/skyframe:SkyframeTests \
  //src/test/java/com/google/devtools/build/skyframe:SkyframeEvictionTest
```
