# Memory sharing and reduction: current roadmap

Updated 2026-11-06. Task-level acceptance remains open; completed implementation
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

The subsequent four-VM staging-cache diagnostic, with the same explicit policy
in all arms, passes 23 checks per arm. At first analysis, mapped owned charges
are 6,283,304,960 bytes versus native's 5,731,282,944 bytes: approximately 9.6%
higher. These are single-seed intrusive diagnostics, not a reconciled total-host
lifetime result. L-02 parity remains open. Source `25c5664fcb` adds exact arena
rebinding and a generation-checked intrusive full-access diagnostic; all four
affected Java suites pass and its binary/image are pinned. Live Bazel DAX
mechanism qualification now passes 425 checks and 14 captures in two real Bazel
VMs; earlier results remain pinned to their actual older binary.

A separate stock-Firecracker PMEM snapshot byte pilot passes 56 checks and 14
captures, with 4 MiB shared selected residency versus 8 MiB for independent
backing in two VMs. Its committed metadata replays. The subsequent
[actual Bazel/JVM fixture](https://github.com/mattconzen/bazel-shared-graph-bench/tree/de420d8/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/bazel-pmem-evidence)
demonstrates 31,363,072 rounded resident arena bytes shared by two VMs versus
62,726,144 with independent backing, through branch divergence, JVM GC, held
pressure, guest refault, actual host backing eviction/refault and revert. Full
retained-image and portable metadata replay pass. This is an intrusive complete
access test, not unperturbed lifetime qualification or complete-host savings.
Failed and source-closure-limited attempts remain preserved. The GB-scale
full-native Envoy executable and repeat
builds have completed. Stock analysis-cache and complete-state discard controls
also pass, producing the same verified executable digest. Their logical-heap
results and the initial [payload audit](docs/shared-graph/payload-audit.md) do not
establish total-host savings. Outside-memory accounting, payload selection and
all final qualification gates remain open.

A separate one-VM, 42-operation ordinary-build screen includes all edits,
errors/recovery, GC and held pressure/refault. Sampled owned mean charges are
4,491,426,356 bytes for warmed native, 2,533,904,261 with explicit per-owned-file
cache advice, and 2,715,576,420 for mapped PMEM plus the same advice. The simpler
native control is better in this short screen; no backend incremental saving is
claimed. All arms complete the same work. In the completed four-VM screen,
native's owned mean is 7,781,932,366 bytes, patched-off is 7,836,656,249,
native with advice is 5,948,387,105 and mapped PMEM with advice is 6,888,651,917.
The mapped combination is about 11.5% below ordinary native but 15.8% above
native with the same advice. These single-template runs are shorter than 600
useful seconds and lack complete-host accounting. Their
[ordinary-screen evidence](https://github.com/mattconzen/bazel-shared-graph-bench/tree/e0d4ff1/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/ordinary-screen-evidence)
includes raw work and source closure; no incremental backend saving is claimed.

A separate native version-file recompile and full static link now has sampled
process attribution. Server-JVM PSS reaches 2,958,432,256 bytes, linker PSS
3,071,634,432 and compiler PSS 673,811,456 at different times. At the linker peak,
the overlapping unit file charge is 9,487,806,464 bytes. These quantities must
not be summed. The executable digest matches the earlier full build. Cold-build
compiler peak coverage remains open; the
[phase evidence](https://github.com/mattconzen/bazel-shared-graph-bench/tree/0dfd594/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/native-phase-and-lifecycle-failures)
states that limitation and retains all three earlier failed lifecycle attempts.

The subsequent lifecycle fixture passes 55 behavior/integrity checks and 335
source-continuity checks across two independently prepared generations, with up
to three live VMMs. It verifies native fallback after protection loss, output
repair with preserved metadata, quarantined generation admission, rollback to
current branch inputs, clean/rebuild with mapped siblings, private-budget
fallback and retirement after all readers exit. The guest now retires and lazily
detaches its own immutable bind views after full discard/reset, allowing native
repository replacement. The diagnostic's owned peak is 13,246,414,848 bytes with
zero swap, OOM or owned-limit reclaim; it is not a lifetime saving comparison.
The [lifecycle bundle and replay tool](https://github.com/mattconzen/bazel-shared-graph-bench/tree/68b76c9/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/generation-lifecycle-evidence)
preserve raw commands, exact sources and five rejected forged-receipt controls.
The follow-up candidate-policy lifecycle fixture now passes 59 behavior/integrity
and 336 source-continuity checks with guest reporting and G1 heap release enabled.
It repeats twelve native action/output comparisons, renewal, repair, fallback,
rollback, clean/rebuild with live mapped siblings, a real private budget and
immutable backing/retirement checks. Its sampled owned peak is 14,039,101,440
bytes with zero swap, owned-limit reclaim or OOM. The
[candidate-policy lifecycle bundle](https://github.com/mattconzen/bazel-shared-graph-bench/tree/30c5d6a/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/policy-generation-lifecycle)
includes raw JVM/configuration evidence, a portable replay and nine rejected
forged-receipt controls. This closes the policy-specific safety validation; it
does not establish lifetime or total-host memory savings.
Guest free-page reporting and G1 heap release remain opt-in, with unchanged
nominal RAM and maximum heap. Their one-VM combined native screen completes the
same 42 operations with mean owned charge 2,164,148,677 bytes. Actual terminal
heap commitment is 310,378,496 bytes with 280,574,976 used, versus reporting
alone's 787,480,576 committed and 281,327,616 used. These logical quantities do
not establish reclaimed physical bytes. The mapped combination completes the
useful work and terminal native actions with sampled owned mean 2,420,212,989,
but its terminal page observer fails on split RAM mappings. It remains failed,
and its recovered raw JVM text is unavailable. No backend advantage is claimed.
The guest order setup and physical observer have bounded corrections. The fresh
four-VM screen now passes all four arms: ordinary native mean owned charge is
7,813,791,985 bytes, patched-off 7,589,238,558, optimized native 4,897,742,853 and
mapped PMEM with the same policies 5,448,544,503. Mapped is 30.3% below ordinary
native but 11.2% above optimized native in these short, single-template screens.
The terminal page observer now passes, recording 125,452,288 selected mapping
bytes backed by 31,363,072 unique file bytes, zero private copies and four-way
multiplicity. All traces remain shorter than 600 useful seconds and unresolved
outside-memory/counter error prevents a complete-host result. Successful retired
images were removed; these older screens lack retrospective full baseline hashes.
The [four-VM policy evidence](https://github.com/mattconzen/bazel-shared-graph-bench/tree/400b2cd/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/four-vm-policy-screen)
retains raw sources/work/page/JVM observations, four portable replays and eighteen
rejected forged-receipt controls. New opt-in ownership diagnostics support split
RAM and export PNG treemaps; new runs also verify complete baseline asset hashes
after all readers close, outside useful measurement. Those tool additions do not
upgrade the older measurements. All final memory gates remain open.
Matched four-VM policy ownership diagnostics now pass their full useful trace,
post-shutdown baseline hashes and portable physical replay. Final cohort VMM PSS
is 3,497,595,904 native versus 3,617,953,792 mapped: 120,357,888 bytes higher for
mapped, including approximately 110,317,227 extra private Java-heap bytes. Native
and mapped retain substantial private JVM native pages, file cache, slab and
allocator-free guest pages; the encoded arena is not a ceiling on this wider
opportunity. Probe effects are substantial and owner uncertainty remains about
9.5% native / 8.9% mapped, so the default 5% ownership regression guard correctly
fails. These snapshots do not qualify unperturbed lifetime savings. The
[ownership bundle and PNGs](https://github.com/mattconzen/bazel-shared-graph-bench/tree/88f2df9/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/policy-memory-attribution)
retain raw evidence, 18 PNGs, native/mapped comparison tooling, portable replay,
17/21 rejected altered receipts and explicit failed-render/supervisor records.
Full lifecycle backing images also pass an independent rehash after all readers
exit. Eight-VM short capacity screening completes all four arms. Native mean
owned charge is 12,011,073,669 bytes, patched-off 12,108,960,967, optimized native
8,010,206,068 and mapped with the same policies 8,696,474,230. Native whole-case
peak is 14,260,097,024 bytes and minimum host MemAvailable 17,178,714,112.
These screens use 128 MiB held pressure per guest; they do not satisfy the
representative contract's 512 MiB pressure requirement. All four portable raw
replays and 68 altered-arm-receipt controls pass. The
[capacity bundle](https://github.com/mattconzen/bazel-shared-graph-bench/tree/1729523/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/eight-vm-capacity)
also preserves raw kernel/cache diagnostics and seven rejected corruptions.
Kernel residuals remain unassigned and cannot substitute for outside-memory
bounds. Independent comparison seeds remain pending. Two complete epochs of the
separate four-epoch coverage pilot take 839.738615535 useful seconds. The
[committed predeclared plan](https://github.com/mattconzen/bazel-shared-graph-bench/tree/d080b7c/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/independent-trials)
pins that 1,087-operation trace, three independent blocks per arm/count, four
arms, four/eight VMs, analysis tools and 512 MiB held pressure per guest. The
[coverage pilot](https://github.com/mattconzen/bazel-shared-graph-bench/tree/af098ed/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/useful-coverage-pilot)
now completes all 2,169 operations over 1,640.751236496 useful seconds, final
baseline hashes and portable raw replay. Its fifteen altered-receipt controls
pass. It retains its screening flag and 128 MiB pressure scope. Trial admission
fails at the fresh eight-VM native 512 MiB capacity check: guest zero OOM kills
the pressure helper after 29 of 42 operations. The failed case receives no
memory/admission credit; plan 01 is preserved unchanged and will not run. New
guest OOM counter checks supplement host events and helper liveness. Fresh
native references are being collected with **3 GiB guest RAM equally for every
arm**, preserving the 768 MiB heap and 512 MiB pressure. All seven fresh native
reference states now complete with equivalent input/action/output/error contracts.
Eight-VM native/off screens complete 42 operations, final baseline hashes and
guest/host safety checks; each rejects seventeen altered receipts. Native mean
owned charge is 15,429,079,566 bytes and off 15,995,532,072, with short useful
windows of 176.109/202.066 seconds. The mapped preflight then rejects missing
guest counters in its specialized DAX response, before useful work. The
[preserved preflight bundle](https://github.com/mattconzen/bazel-shared-graph-bench/tree/c0ad1db/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/three-gib-reference-and-preflight)
retains the failed master/mapped case and completed native/off children.
The metadata correction appends real guest counters while retaining unavailable
DAX RSS. The fresh mapped admission screen now completes all 42 operations and
final baseline hashes, with zero guest/host OOM, swap or limit events. Its owned
mean is 12,519,049,872 bytes over 209.806 useful seconds. Terminal page evidence
records eight-way sharing: 250,904,576 mapping bytes backed by 31,363,072 unique
file bytes and zero private copies. Raw replay and twenty-three altered-receipt
controls pass. Optimized-native admission also completes all 42 operations and
final baseline hashes with zero guest/host safety events. Its owned mean is
11,820,188,683 bytes over 195.508 useful seconds; mapped is about 5.9% higher than
that native control with the same policies. Its nineteen altered-receipt controls pass. Both arms now have
[committed corrected-source admission and portable replay](https://github.com/mattconzen/bazel-shared-graph-bench/tree/7117e40/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/three-gib-policy-admission).
These separate short screens do not qualify a complete-host or independent
lifetime comparison. A fresh four-VM patched-off coverage pilot is running at
3 GiB RAM and 512 MiB pressure, with four fixed epochs and 2,169 operations.
The treemap policy collector now supports explicit 2/3 GiB geometry and exact
legacy replay; live 3 GiB ownership capture remains required.
The subsequent [raw owner-input tooling](https://github.com/mattconzen/bazel-shared-graph-bench/commit/ae0317e)
preserves the before/after kernel page flags and observed userspace masks, so
future physical replays can recompute ownership labels and investigate unknown
frames. Thirty-six focused checks pass under both capture/render Python runtimes;
the earlier terminal four-VM native capture replays with exactly unchanged
attribution. No unknown frames have been newly assigned and the 5% guard remains
unchanged. Live schema-2 ownership capture is still pending. New capacity,
coverage, sharing/lifecycle and plan pins are required before independent collection. No independent
seed has launched. No idle padding or unequal per-arm work is permitted.
The [policy-screen bundle](https://github.com/mattconzen/bazel-shared-graph-bench/tree/d946bf8/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/policy-screen-preflights)
retains successful screens, failed attempts, scope limits and four portable
replays with fourteen rejected forged-receipt controls.

The available host has 32 GiB RAM. The user confirmed that no larger host is
available. Qualify local four/eight-VM configurations only where they fit without
host pressure; leave 20+ VMs untested. A marginal-memory projection cannot
qualify an unavailable scale, and no larger-host provisioning is authorized.

## L-01 — Identify the GB-scale opportunity and establish controls

**Related obligations:** SG-001, SG-002, SG-004, SG-009.
**Dependencies:** existing measurements and reproducible native builds.

### Tasks

- [x] Profile unmodified native Bazel through the representative Envoy workload,
  including a full executable build where needed to reproduce the claimed
  GB-scale footprint. Label analysis, compilation and linking separately.
- [x] Retain the current analysis/version-library regression fixture and a
  separately pinned larger full-executable native protocol. See the benchmark
  fork's `LARGE-ENVOY-PROTOCOL.md` and completed 4 GiB-heap host diagnostic.
- [ ] Admit any larger full-executable VM comparison using enough memory for
  native to complete, then match resources across arms. No larger guest image or
  four-VM full-build comparison is yet qualified. Do not treat the current
  768 MiB-heap case as representative by assumption.
- [ ] Attribute live graph payloads, dependency storage, keys/indexes, committed
  heap, allocation/GC-related private pages, JVM native memory, compiler/linker
  processes, kernel memory and file/buffer cache at matching checkpoints.
- [ ] Reconcile the complete host-memory ledger, including baseline backing and
  support memory outside VMM mappings. Quantify accounting error and unknowns.
- [x] Keep unmodified warmed native as the primary control and patched-off as
  the implementation control. Add a separate native memory-saving control using
  applicable stock cache-discard options. Record exact options and verify its
  outputs; it must not replace the primary warmed-native control.
- [ ] Rank opportunities by attributable private resident bytes, semantic reuse,
  GC/write behavior and expected replacement overhead. Include opportunities
  outside the current mapped dependency vectors.
- [ ] Pin source/binary/image identities, toolchains, observed JVM/GC settings,
  execution/cache policy, input states, resources, useful work and memory
  checkpoints. Predeclare the primary comparison and decision rule. The committed
  plan 01 and lifetime protocol pin the primary 600-second interval, raw replay,
  paired seed arithmetic and unknown complete-host bounds. Plan 01 was never
  run and is superseded after failed guest-pressure admission. Reopen these pins
  for the matched 3 GiB runtime; prior diagnostics keep their original identities.

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
  The [retention audit](docs/shared-graph/restore-retention-audit.md) distinguishes
  required fallback observations, released construction locals and remaining
  measured view/record overhead. Its possible wrapper reduction is not implemented
  or counted as physical savings.
- [ ] Preserve detection of size/mtime-preserving edits, directory/glob/symlink
  changes, missing or changed outputs, watcher discontinuity and independent
  sibling edits. Keep failed reconciliation closed to ordinary commands.
- [x] Build and pin the corrected binary/image, run affected semantic/integrity
  suites, and collect matched before/after memory attribution at the declared
  physical stages. Current policy diagnostics cover before/after reconciliation,
  held pressure and post-useful work; full action observers follow those captures.
  Keep diagnostic captures separate from lifetime comparisons.

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

- [x] Follow declared arena pages from preparation through restore,
  reconciliation, useful reads, branch edits, GC, held pressure, eviction and
  useful refault. Separate normal pressure from deliberate guest-cache eviction.
- [x] Record guest-page identity, host backing object/offset, resident coverage,
  shared/private bytes and frame multiplicity where observable. Locate the first
  transition that creates private copies and identify its cause or uncertainty.
- [x] Establish equivalent shared and private-copy controls with the same
  representation and access coverage. Verify byte contents and write isolation.
- [x] Correct the backing or lifecycle behavior responsible for losing sharing.
  If that requires a different host-sharing mechanism, document and test a bounded
  design before integrating it; fork affected external components under
  `mattconzen` before modification.
- [ ] Include locked/prefaulted pages, retained source copies, mapping metadata
  and any pager/helper memory in the ledger if those mechanisms are used.
- [x] Replay the page evidence and reusable regression checks from committed
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
- [x] Record the selected bounded change and expected removable bytes before
  implementation, or explicitly defer candidates without a demonstrated
  opportunity. The [value-family API audit](docs/shared-graph/payload-api-audit.md)
  records no new family selected: concrete caller/clearing contracts and unknown
  replacement/resident costs do not justify expansion. The previously selected
  controller/JVM policies retain their separate pre-implementation estimate.
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
- [x] Validate private-memory budgets, fallback on invariant/integrity failure,
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
