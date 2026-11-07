# Bounded mapped-node view cleanup experiment

Selected before implementation on 2026-11-07, following the independent policy
matrix. This is L-02 adapter overhead cleanup, without selecting a new L-04 value
family or changing the encoded arena. No resident saving is yet demonstrated.

## Opportunity and expected cost

The earlier clone-zero post-action/full-GC histogram records 187,375 frozen
nodes, with 5,996,000 shallow bytes each for direct and reverse views, 4,497,000
for records and 2,998,000 for frozen state. At that observed compact-header
layout, a per-node state containing store, record index, value and version is
estimated at 24 bytes instead of the current aggregate 104. The removable live
shallow estimate is therefore 14,990,000 bytes per VM. Heap commitment, placement,
caller retention and allocations prevent converting this estimate to resident
bytes. It does not explain the entire observed mapped/native gap.

Create direct/reverse immutable views only when a caller requests them; do not
retain the views in node state or add a decoded cache. Value, metadata and
version reads use the per-node fields directly. Dependency-count queries should
read the validated arena rather than allocate views. Promotion constructs a
single ordinary record and delegates to the existing native restoration path.
Temporary view creation and GC are replacement costs to measure, including
preparation peaks and the useful trace. Retain this change as a memory improvement
only after a matched physical comparison supports a net reduction beyond the
measurement uncertainty. Old evidence stays pinned to the old implementation.

## Contract audit

`DoneDeps` promises ordered, repeatable, immutable views and canonical key
identity, with retained readers valid after dirtying. It promises no identity
for the reader object. Its default native implementation already constructs a
reader per call. Graph statistics and evaluator dumping consume counts/groups;
walkable-graph adapters return dependency iterables or collections to callers.
Invalidation consumes direct and reverse dependencies. Those callers need stable
contents and lifetime, not one cached wrapper per node. Reverse lists retain
ordinary list equality/hash/traversal semantics and forbid mutation.

Views must hold only the mapped store and offsets/counts. The store owns canonical
keys and backing lifetime, without an array of all Java values. A retained reader
must not pin unrelated or obsolete Java values. Per-node value/version identities
remain unchanged. Exact rebinding must continue to work for already returned
views. Promotion, failed construction/publication rollback, concurrent operations,
deleting graph membership and retirement must preserve their existing behavior.

## Required validation

- Existing mapped arena integrity, grouping, canonical-key, promotion, concurrency,
  rollback, rebinding and retained-reader tests pass.
- New reachability checks show requested views are collectable while the frozen
  node remains live; retained readers do not retain a removed node's value.
- Ordinary executor, lifecycle and runtime suites pass for the changed source.
- Build/pin a new binary and derived image before live tests. Re-run lifecycle
  and useful-work checks against fresh native references. Compare old/new mapped
  candidates under identical policies; preserve intermediate/peak costs and
  private growth, with no speed or execution-time gate.
- Keep L-02 parity, complete-host accounting and the overall 25% objective open
  until their own criteria pass. A lower shallow histogram is insufficient.
