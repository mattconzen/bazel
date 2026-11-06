# Memory sharing and reduction: current roadmap

Updated 2026-11-05. Task-level acceptance remains open; completed implementation
subtasks are checked below.

Reduce total resident host memory across related Bazel microVM builds while
preserving correctness, branch isolation and safe lifecycle handling. Demonstrate
physical sharing through useful work, GC, pressure and refault. Target the
GB-scale private footprint; the current dependency arena's encoded size does not
bound possible whole-VM savings.

This document sets the next execution order. [TICKETS.md](TICKETS.md) retains the
original-plan obligations and historical stage decisions. The
[current experiment contract](docs/shared-graph/experiment-contract.md) defines
the memory-first scope. Reopening payload attribution and selection here does
not authorize an unmeasured adapter or make an earlier failed gate pass.

## Objectives and measurement rules

- Retain the target of at least **25% lower total resident host memory** against
  unmodified warmed Bazel snapshots. The primary measure for this iteration is
  the time-weighted mean over the first 600 seconds of useful post-reconciliation
  lifetime. Include all retained baseline and supporting memory during that
  window. Report preparation/restore peaks and operational peaks separately.
- Publish absolute bytes, per-seed comparisons, uncertainty and marginal memory
  per additional VM. Predeclare aggregation and the decision rule before new
  comparison runs. Missing accounting or uncertainty that prevents a decision
  leaves the result inconclusive.
- Require zero observed semantic or isolation mismatches, released storage for
  every claimed offloaded family, immutable baseline bytes, and safe fallback
  and generation retirement.
- Remove all speed and execution-time goals. CPU, latency/p95, throughput,
  startup, preparation and reconciliation duration have no targets or gates.
  Their existing counters are optional diagnostics. Ten minutes is a memory
  observation window, not a build-completion deadline.
- Keep useful work and resource configuration matched within each comparison.
  Do not count idle time, skipped work, killed pressure helpers, OOM, swap or
  host-limit-induced reclaim as evidence of a memory saving.
- Count participating VMMs, controllers, helpers, JVM native memory, guest and
  host cache, kernel charges and retained snapshot backing without overlap.
  PSS, cgroup charges and backing cache must not simply be summed. Keep logical
  heap bytes, guest residency and host physical memory as separate quantities.
- Use the existing collectors, treemaps and regression tools. Keep intrusive
  heap/file/page diagnostics separate from unperturbed memory comparisons.
  Record observer effects, coverage and unknown ownership; preserve raw evidence.
- All repository changes and PRs must stay under `github.com/mattconzen`.
  Fork any external component under `mattconzen` before modifying it. Keep the
  backend opt-in and preserve the existing host-policy constraints.

## Execution order

1. Start L-01 baseline/accounting and L-02 restore correction. Begin the L-04
   payload audit using existing evidence, updating it when L-01 completes.
2. Diagnose the L-03 sharing boundary during that work; qualify the corrected
   backing path after L-02.
3. Implement a selected L-04 payload only after L-03 proves the backing mechanism
   and the payload audit establishes a measurable opportunity.
4. Run L-05 on the qualified candidate, close lifecycle gaps, and publish the
   memory acceptance decision. A payload is unnecessary if another qualified
   change already meets the objectives.

## Implementation checkpoint

Source `162c8248c689f447e35a8fbcee57a2f2be9afdca` and controller `768265e`
implement opt-in [immutable input roots](docs/shared-graph/immutable-inputs.md)
and [controller-verified unchanged filesystem clones](docs/shared-graph/verified-clean-clones.md).
Four affected Java suites pass. The live one-VM clean-clone preflight passes
eight fresh-native action/output, admission and integrity checks. Reconciliation
reads zero regular-file content bytes, using clone continuity for 53,974 saved
observations; arena verification remains required. This completes a scan-path
correction, not four-VM memory parity or the overall reduction objective.

A separate stock-Firecracker PMEM snapshot byte pilot passes 56 checks and 14
captures, with 4 MiB shared selected residency versus 8 MiB for independent
backing in two VMs. Its committed metadata replays. It covers held guest pressure
and guest mapping/refault; Bazel integration, JVM GC and host backing-cache
eviction/refault remain open. The GB-scale full-native profile is still running.
Outside-memory accounting, the native memory-saving control, payload selection
and all final qualification gates remain open.

## L-01 — Identify the GB-scale opportunity and establish controls

**Related obligations:** SG-001, SG-002, SG-004, SG-009.
**Dependencies:** existing measurements and reproducible native builds.

### Tasks

- [ ] Profile unmodified native Bazel through the representative Envoy workload,
  including a full executable build where needed to reproduce the claimed
  GB-scale footprint. Label analysis, compilation and linking separately.
- [ ] Retain the current analysis/version-library case as a regression fixture.
  Add a separately pinned larger-workload protocol when necessary; provision
  enough guest/host memory for native to complete and match resources across
  arms. Do not treat the current 768 MiB-heap case as representative by assumption.
- [ ] Attribute live graph payloads, dependency storage, keys/indexes, committed
  heap, allocation/GC-related private pages, JVM native memory, compiler/linker
  processes, kernel memory and file/buffer cache at matching checkpoints.
- [ ] Reconcile the complete host-memory ledger, including baseline backing and
  support memory outside VMM mappings. Quantify accounting error and unknowns.
- [ ] Keep unmodified warmed native as the primary control and patched-off as
  the implementation control. Add a separate native memory-saving control using
  applicable stock cache-discard options. Record exact options and verify its
  outputs; it must not replace the primary warmed-native control.
- [ ] Rank opportunities by attributable private resident bytes, semantic reuse,
  GC/write behavior and expected replacement overhead. Include opportunities
  outside the current mapped dependency vectors.
- [ ] Pin source/binary/image identities, toolchains, observed JVM/GC settings,
  execution/cache policy, input states, resources, useful work and memory
  checkpoints. Predeclare the primary comparison and decision rule.

### Completion criteria

- The GB-scale footprint is reproduced and its owners are identified. If that
  footprint is predominantly compiler/linker memory, say so explicitly and
  quantify the portion available to a graph-sharing change.
- A reproducible, non-overlapping host-memory ledger supports the claimed scope;
  unresolved bytes and measurement error are small enough to assess the target.
- Ordinary warmed native, patched-off and the native memory-saving control have
  auditable configurations and correctness evidence for equivalent useful work.
- The ranked opportunity list reports absolute bytes and assumptions. Retained
  object sets are not added when they overlap, and no arena-size ceiling is
  substituted for physical-memory attribution.

## L-02 — Remove the restore memory regression

**Related obligations:** SG-005, SG-006, SG-008.
**Dependencies:** existing restore/cache evidence; L-01 for complete-host comparison.

### Tasks

- [x] Replace blanket regular-file content scanning with trustworthy filesystem
  change evidence. Distinguish immutable template/toolchain inputs, mutable
  workspace/output state and observations whose history cannot be trusted.
- [x] Reuse Bazel's existing invalidation machinery where its evidence remains
  valid across restore. Define the trust boundary explicitly; use safe content
  revalidation or native fallback when continuity is unavailable.
- [ ] Cover both template preparation and clone restoration. Remove avoidable
  toolchain-cache population, retained observation tables, temporary copies and
  construction buffers; preserve required arena/integrity checks.
- [ ] Preserve detection of size/mtime-preserving edits, directory/glob/symlink
  changes, missing or changed outputs, watcher discontinuity and independent
  sibling edits. Keep failed reconciliation closed to ordinary commands.
- [ ] Build and pin the corrected binary/image, run affected semantic/integrity
  suites, and collect matched before/after memory attribution at the existing
  physical stages. Keep diagnostic captures separate from lifetime comparisons.

### Completion criteria

- Unchanged inputs covered by the declared immutable-input trust contract cause
  no blanket toolchain-content scan. Remaining content reads and their correctness
  justification are recorded in bytes; no execution-time target applies.
- Fresh-native output/action checks and the changed failure-path tests pass.
  Missing change evidence causes safe revalidation/fallback, not stale reuse.
- Matched physical attribution shows the excess private cache and other restore
  overhead removed or explicitly explains the residual bytes. Changes are judged
  by resident memory rather than reduced allocation or I/O alone.
- The corrected four-VM candidate reaches memory parity with warmed native under
  the predeclared measurement/error rule. This is a repair milestone; the overall
  25% reduction objective remains outstanding until L-05 passes.

## L-03 — Prove durable physical sharing

**Related obligations:** SG-003, SG-004, SG-008, SG-015.
**Dependencies:** diagnose now; qualify the corrected path after L-02.

### Tasks

- [ ] Follow declared arena pages from preparation through restore,
  reconciliation, useful reads, branch edits, GC, held pressure, eviction and
  useful refault. Separate normal pressure from deliberate guest-cache eviction.
- [ ] Record guest-page identity, host backing object/offset, resident coverage,
  shared/private bytes and frame multiplicity where observable. Locate the first
  transition that creates private copies and identify its cause or uncertainty.
- [ ] Establish equivalent shared and private-copy controls with the same
  representation and access coverage. Verify byte contents and write isolation.
- [ ] Correct the backing or lifecycle behavior responsible for losing sharing.
  If that requires a different host-sharing mechanism, document and test a bounded
  design before integrating it; fork affected external components under
  `mattconzen` before modification.
- [ ] Include locked/prefaulted pages, retained source copies, mapping metadata
  and any pager/helper memory in the ledger if those mechanisms are used.
- [ ] Replay the page evidence and reusable regression checks from committed
  metadata. Preserve failed or coverage-limited captures explicitly.

### Completion criteria

- Common, unchanged data that the declared workload accesses has demonstrated
  shared host backing after reconciliation, GC and pressure, and after useful
  refault. Read-only guest mappings and identical bytes alone do not satisfy this.
- Under equivalent full access, the shared control approaches one physical copy
  of each common resident page within declared accounting tolerances; the private
  control exposes the corresponding replication. Report subset coverage plainly.
- Sharing denominators come from semantically reusable baseline bytes and the
  declared working set. Reclaimed, unread or unknown pages cannot silently make
  the sharing percentage improve.
- No cross-VM mutation or private reconstruction of the complete declared shared
  payload occurs. Supporting memory does not erase the measured benefit.
- If refault still creates private copies, record the mechanism as failing or
  limited to its demonstrated residency envelope. Broader payload implementation
  remains gated on a backing path that meets the declared durability objective.

## L-04 — Select and offload the dominant payloads

**Related obligations:** SG-002, SG-003, SG-011, SG-012; SG-013 if applicable.
**Dependencies:** audit starts now; selection uses L-01; implementation requires L-03.

### Tasks

- [ ] Reopen payload attribution and selection. Rank actual large value families,
  keys/indexes, dependency structures and retained caches by recoverable private
  resident bytes and read/materialization behavior.
- [ ] Estimate each candidate's net memory effect, including shared backing,
  indexes, wrappers, adapters, decoded caches, temporary buffers, caller-retained
  materializations and continued Java identity requirements.
- [ ] Audit equality, hashing, identity, traversal, mutation and lifetime contracts
  for the selected family. Select nested sets only with their separate identity
  design and tests; do not assume a concrete subclass is interchangeable.
- [ ] Record the selected bounded change and expected removable bytes before
  implementation. Defer candidates without a demonstrated opportunity.
- [ ] Implement one family at a time using the backing proved in L-03. Bound
  materialization caches by bytes and generation and keep mutable state private.
- [ ] Prove replaced Java storage and construction buffers are released. Test
  ordinary callers, branch divergence, edit/revert, GC, pressure and retirement.
- [ ] Repeat matched physical-memory measurements and correctness comparisons
  for each retained change, including peaks and intermediate copies.

### Completion criteria

- Selection is based on measured bytes and compatible APIs, with an auditable
  before/after estimate. The largest family is not automatically safe to offload.
- Each implemented adapter preserves semantics and lifetime safety, releases the
  claimed old storage, and avoids rebuilding an equivalent complete private copy.
- Each retained change produces a net resident host-memory reduction after all
  replacement costs, beyond the declared uncertainty. Smaller encodings or lower
  live heap alone do not qualify.
- The memory-saving native control remains a separate comparison. Explain the
  shared backend's incremental memory value relative to that simpler option.
- If no payload is justified, publish the measured limitation and deliberate
  next direction rather than expanding the format without evidence.

## L-05 — Validate density, lifecycle safety and the final memory result

**Related obligations:** SG-007, SG-008, SG-009, SG-010, SG-014 through SG-017.
**Dependencies:** L-01 controls/accounting and a candidate qualified by L-02/L-03;
L-04 implementation if a selected payload contributes to the candidate.

### Tasks

- [ ] Compare four, eight and at least twenty simultaneous VMs where adequate
  unconstrained capacity is available. Retain one/two-VM controls where useful
  for estimating marginal memory. Leave unavailable scales explicitly untested.
- [ ] Use at least three independent seeds per arm/count for each configuration
  being qualified, counterbalance order, and add seeds when uncertainty prevents
  a decision. Never treat repeated samples from one seed as independent trials.
- [ ] Observe at least ten useful minutes per qualified cohort. Exercise no-ops,
  leaf/BUILD/Starlark and high-fanout edits, sibling divergence, revert,
  errors/recovery, repeated GC and the representative held-pressure/refault case.
- [ ] Preserve fresh-native correctness checks, equivalent completed useful work
  and explicit verification/observer memory effects across arms.
- [ ] Report time-weighted mean total resident host memory, preparation/restore
  and operational peaks, lifetime growth, marginal memory per added VM, physical
  sharing and absolute savings. Publish the full duration and sampling coverage.
- [ ] Validate private-memory budgets, fallback on invariant/integrity failure,
  failed-reconciliation admission, rollback, shutdown/reset/clean with live
  readers, generation renewal and safe retirement. Count these controls' memory.
- [ ] Publish a pass/fail/inconclusive decision against the current objectives,
  with exact source/binary/image identities, per-seed evidence and exclusions.
  Keep defaults off and any rollout conditional on passing memory and safety
  gates; speed qualification is not required.

### Completion criteria

- At least 25% lower time-weighted mean total resident host memory than the
  unmodified warmed-native control is supported under the predeclared decision
  rule for each claimed configuration. A four-VM result does not qualify twenty
  VMs; report each scale's result separately.
- Peaks, supporting memory, accounting error and uncertainty are visible. Smaller
  footprints caused by OOM, swap, host caps, incomplete work or killed pressure
  helpers cannot support the saving claim.
- Physical sharing and net memory savings survive the declared useful workload,
  GC, pressure and refault sequence, with bounded private and obsolete-generation
  growth and zero observed semantic/isolation mismatches.
- Budget enforcement and failures cannot resurrect stale state or retire backing
  still used by a reader/VM. Native fallback and generation cleanup are tested.
- Reusable collectors, treemaps, raw receipts and replay/regression commands are
  committed in the owned forks. Completion claims stay pinned to the tested code;
  unresolved scenarios and failed gates remain open in the published decision.

## Existing evidence to reuse

- [Current requirement/evidence index](docs/shared-graph/obligation-evidence.md).
- [Memory measurement and reusable-tool methodology](docs/shared-graph/measurement.md).
- [Production/scaling, physical attribution and arena evidence](https://github.com/mattconzen/bazel-shared-graph-bench/tree/861c47faa602110c7583bf25e31e2e4e3a43ba12/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-04-plan-obligations).
- [Per-file guest-cache attribution and rejected observer methods](https://github.com/mattconzen/bazel-shared-graph-bench/tree/861c47faa602110c7583bf25e31e2e4e3a43ba12/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-file-cache).
- [Stock Bazel memory-saving controls](https://bazel.build/advanced/performance/memory).
- [Firecracker snapshot backing semantics](https://github.com/firecracker-microvm/firecracker/blob/v1.17.0/docs/snapshotting/snapshot-support.md).

Historical bundles retain their original criteria and negative outcomes. Their
CPU/latency gates are superseded by the current user amendment. Existing
measurements provide the baseline; they do not complete these new tasks or
establish a saving for a later binary.
