# Shared graph: remaining implementation-plan obligations

Complete the original experiment: keep unchanged build-graph data on the same
physical pages across related microVM builds, preserve ordinary incremental
behavior, and demonstrate a net host-memory benefit against unmodified warmed
Bazel snapshots.

Source: `Bazel_Shared_Graph_Implementation_Plan.docx`, supplied in this thread.
Document SHA-256:
`f7fe7e818d38f9a98bcde497d89fd7f72c5f10ed2f5329048ec1601c0845c8c4`.
Section numbers and PR stages below refer to that document, not GitHub PR numbers.

## Current objectives — user amendment, 2026-11-05

Prioritize physical memory sharing and lower total resident host memory. The user
removed all speed and execution-time goals: CPU overhead, latency/p95, throughput,
preparation time and restore/reconciliation time are no longer targets, acceptance
gates, rollout blockers or required optimization work. This supersedes those parts
of the original plan and the earlier proposed one-second restore target. Existing
timing/CPU measurements may remain as optional diagnostics; they do not determine
success. The ten-minute VM lifetime is a memory-durability observation window.

Retain the 25% host-memory reduction target, correctness, physical-sharing proof,
bounded private growth, and durability through useful work, GC and pressure. The
[current experiment contract](docs/shared-graph/experiment-contract.md) controls
future decisions; immutable historical reports retain their original criteria.

## Starting point at the original backlog review

Reviewed Bazel revision: `832e42bc589a6c77516b8d01b4476ccd80ac2b77`.
At that review, the latest tested binary was built from
`8ff24041b3b56434884157474972ce4d30510bed`; the subsequent commit changes
measurement documentation only. Controller/evidence revision:
`69788d7d394521195b47e0811832b84bee27dc81`.

The graph factory, dependency readers, stable handles, heap baseline, mapped
direct/reverse vectors, native promotion, preparation barrier, GC/prefaulting,
restore reconciliation and explicit discard already exist. Preserve and extend
that work. An implemented component does not imply its plan stage passed.

The [corrected Envoy results](https://github.com/mattconzen/bazel-shared-graph-bench/blob/69788d7d394521195b47e0811832b84bee27dc81/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-04-envoy-reuse/README.md)
show zero unchanged-restore promotions, but mapped VMM PSS is 11.7% higher at two
clones and 28.8% higher at four. The 1,670 comparison/recovery correctness checks
passed; both arms failed the complete 768 MiB sustained-pressure profile.
Retained-memory attribution, complete host accounting and durable host sharing
remain unresolved. Keep the backend off by default.

## Execution status

The production target is four clones with a ten-minute lifetime, as specified
by the user. [The experiment contract](docs/shared-graph/experiment-contract.md)
fixes the comparison and [the evidence index](docs/shared-graph/obligation-evidence.md)
tracks implemented checks, failed diagnostic attempts and unresolved gates.
The measured first correction reuses fingerprint buffers; its source tests pass,
and a pinned probe reduces fingerprint allocation from 1,502.68 to 41.96 MiB per
clone. Allocation reductions do not establish a host-memory saving.
An opt-in real-graph weak-reference audit is implemented and tested, including
a deliberately retained array that must be reported as surviving. Matched
four-arm diagnostic collection and the two-clone retention/buffer and native
fallback probes are complete. [Published attribution evidence](https://github.com/mattconzen/bazel-shared-graph-bench/tree/21b343bbb24bcf0e0c4adae35089b1f31f93968e/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-04-plan-obligations)
separates retained objects, allocation and physical memory. The second measured
correction streams the saved validated-arena digest during restore. Its corruption
and restore regression suites pass; its matched after-probe passes thirteen
checks and measures 86,720 bytes allocated for validation per clone, versus
221.85 MiB before. All twelve production lifetime cases and all twelve scaling
cases are complete, with 6,712 passing correctness checks.
All three native/mapped production pairs pass correctness and sustained pressure
without host-limit or swap exclusions. Mapped VMM PSS is 26.4–29.7% higher than
native over the first ten minutes (median 27.9%). The disabled control ranges
from 1.4% lower to 1.4% higher PSS than native across the three seeds. The
separate four-VM physical attribution is complete. Five two-VM operational cases
pass 32 checks; every 16 GiB diagnostic unit hit its cap and is excluded from
performance. Private guest file cache accounts for about 84% of the first-analysis
PSS excess, while post-GC live Java objects remain close to native. Arena sharing
disappears during reconciliation in both capped and uncapped captures. These
scope-limited results do not establish total-host acceptance. No gate is inferred
from these implementation checkmarks.

## Recorded acceptance decision

**Refine the restore path. Keep native/off as the default, defer payload adapters,
and block canary rollout.** [The immutable gate table](https://github.com/mattconzen/bazel-shared-graph-bench/tree/21b343bbb24bcf0e0c4adae35089b1f31f93968e/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-04-plan-obligations/README.md#acceptance-decision)
records the distinction between evaluating a gate and passing it.

| Gate | Status |
| --- | --- |
| Tested semantic comparisons | 6,712 matrix checks pass; 32 constrained operational checks pass; unsupported/failure scenarios remain open |
| 25% lower total resident host memory | Not achieved; VMM PSS increases 26.4–29.7%, unit charges also increase; exact complete-host gate unresolved |
| Durable shared arena and lifetime savings | Fails for the tested restore path: reconciliation evicts arena pages and subsequent reads refault privately |
| PR1/PR2 disabled-cost gate | Three disabled/native seed pairs range from −1.4% to +1.4% PSS; no fleet/noise bound claimed |
| PR3/PR4 readiness | Replaced storage is released and bytes remain immutable; private-memory benefit and full operational coverage are incomplete |
| Payload/canary | Deferred/blocked, respectively |

This closes the dependency-stage investigation with a negative implementation
result, not the original success criterion or the broader hypothesis. The next
bounded optimization is trustworthy filesystem reconciliation without repeated
full-content scans and their guest-cache duplication; it requires a separate
before/after experiment with the same physical stages and the current memory,
correctness and lifecycle gates.
Unchecked attribution/compatibility items remain genuine work. Conditional
payload tasks do not authorize an adapter, and no rollout is approved.

The reusable collector, renderer, raw metadata and regression CLI are committed
in the owned controller fork. Replay regenerates every output hash without VM
assets. First-analysis unknown ownership is 8.73% for mapped: the default 5%
coverage screen correctly refuses that stage. Restore/post-verification screens
pass; synthetic growth and incompatible inputs fail as expected.

The later preparation guard and failure injections are source-qualified
separately from the measured `bafaaeb157` binary; exact final build qualification
is attached to the fork PR. No measured performance is transferred to later code.

## Execution rules and order

- All PRs must target `github.com/mattconzen`. Use explicit `--repo` arguments.
  Fork any additional component under `mattconzen` before modifying it.
- Assign a graph-semantics owner and a runtime-measurement owner. Both review
  evidence that crosses the graph/VM boundary; the roles are unassigned here.
- Start SG-001, then SG-002 through SG-005. SG-007 and SG-008 can proceed alongside
  measurement. SG-006 contains only fixes justified by the resulting evidence.
- Run SG-009 after fixes and checks, then make the explicit stage decision in
  SG-010. SG-011 through SG-013 are conditional payload work. SG-014 and SG-015
  complete operational controls; SG-016 cannot start a canary before its gates.
- Every unchecked item is unfinished work or an evidence obligation. Reuse valid
  existing tests and receipts; identify gaps before adding or rerunning tests.
- Close a ticket with linked evidence, exact revisions, results and limitations.
  Record a failed gate as failed. A documented stop decision completes an
  investigation, not the original success criterion.
- Keep diagnostic heap dumps/full graph scans separate from performance runs.
  Preserve failed trials, incompatible protocols and excluded samples explicitly.
- Keep stable identity, native promotion/reverse-edge semantics, one frozen
  generation and the snapshot-coupled format. Fine-grained edge deltas, standalone
  JVM import, automatic re-freezing and an alternate host-sharing mechanism are
  separate design changes, not implied tasks in this backlog.

## SG-001 — Fix the experiment contract and evidence baseline

**Plan:** §§1, 8/PR0, 10, 12. **Owner:** both. **Depends on:** none.

- [x] Inventory existing code, tests, raw trials and reports against each plan
  requirement; distinguish implemented, demonstrated, failed and unmeasured.
- [x] Pin source, binaries, image, controller, Envoy revision, toolchains, JVM/GC,
  VM resources, guest paths, snapshot backing, cache warmth and KSM policy.
- [x] Define the representative related-branch workload, expected production
  clone count and VM lifetime. State exactly which analysis, compilation and
  linking work is exercised; existing Envoy runs do not build the full executable.
- [x] Establish the current-protocol unmodified warmed control and its native
  source revision. Keep patched-off as a separate control and cold JVMs as context.
- [x] Predeclare repetitions, ordering, uncertainty estimates, constrained-run
  exclusions and memory-measurement scope. Retain the 25% host-memory target.
  The user amendment above removes speed and execution-time targets; historical
  timing observations are optional diagnostics, not pending obligations.

**Done when:** another engineer can reproduce the comparison and identify which
original obligations each existing artifact actually satisfies.

## SG-002 — Identify where representative graph memory is retained

**Plan:** §§6, 8/PR0, 10, 12. **Owner:** runtime, with graph review.
**Depends on:** SG-001.

- [x] Collect diagnostic retained-heap evidence for native, patched-off, heap
  and mapped arms at comparable warmed, post-conversion, post-restore and
  post-incremental-work phases, with controlled diagnostic GC.
- [x] Attribute bytes to direct/reverse dependencies, values, canonical keys,
  handles, frozen records/readers, indexes, promoted nodes, caches and
  caller-retained materializations. Treat shallow histograms as supporting data.
- [ ] Rank value families by retained bytes, sharing/fanout and materialization
  demand; avoid double-counting overlapping dominator/retainer ownership.
- [x] Correlate retained/live heap with committed and resident heap, native JVM
  allocations and arena residency. Keep object bytes and physical pages distinct.
- [x] Quantify the dependency-only opportunity and the remaining Java overhead.
  Record uncertainty and plausible savings bounds rather than equating encoded
  arena size with removable heap or physical duplication.

**Done when:** the largest memory categories and their retainers are identified
in a reproducible report, sufficient to judge whether dependency storage is a
material opportunity. This closes the missing PR0 attribution requirement.

## SG-003 — Prove offloading releases the original storage

**Plan:** §§5–7, 8/PR3. **Owner:** graph, with runtime review.
**Depends on:** SG-002.

- [x] Audit real Envoy retainer paths for old delegates and dependency arrays,
  construction maps/buffers, key/value tables, interners, serialization memo
  tables, command results, futures, thread locals and queues.
- [x] Verify successful conversion releases obsolete storage after controlled
  template GC. Distinguish legitimate arrays for unfrozen/promoted nodes from
  arrays whose storage is claimed as offloaded.
- [x] Exercise hot dependency consumers and instrumentation; measure remaining
  legacy-array materialization and show ordinary traversal does not silently
  reconstruct or cache the complete graph.
- [ ] Correlate promotion/materialization counters with retained bytes and
  actual recomputation through no-op, edit and revert sequences.
- [x] Verify published arena bytes remain unchanged through useful work, GC,
  pressure and recovery; retain digests and generation identities.

**Done when:** every claimed offloaded storage family has representative
retention evidence, obsolete arrays/delegates are absent, and residual copies
are attributed. Synthetic weak-reference tests alone do not close this ticket.

## SG-004 — Establish host sharing and complete memory accounting

**Plan:** §§7, 8/PR0–PR4, 10. **Owner:** runtime.
**Depends on:** SG-001; use SG-002 attribution as it becomes available.

- [x] Verify clones use the same immutable RAM backing object and offsets;
  record file identity, mapping mode, generation and prefault coverage. Audit any
  custom fault loader rather than assuming it shares the same backing.
- [x] Establish a supported host measurement method for shared versus private
  resident pages across clones. Masked PFNs and guest arena RSS are insufficient
  evidence; document any remaining attribution limit.
- [ ] Account for all participating VMMs, controllers and helper processes,
  baseline backing-file cache not already represented, and kernel charges.
  Reconcile overlapping PSS/cgroup/cache scopes without adding them twice.
- [ ] Measure absolute and marginal memory per clone at restore, reconciliation,
  first work, steady work, GC, pressure and recovery. Include reads that increase
  residency even without dirtying pages.
- [x] Record pressure-induced reclaim and useful refault separately from explicit
  cold-cache eviction. Determine whether host sharing survives each mechanism.

**Done when:** the report can support a total resident host-memory comparison and
an attributed sharing claim, or explicitly records why that gate cannot yet be
evaluated. A measurement limitation does not count as a passed gate.

## SG-005 — Attribute restore and frozen-node overhead

**Plan:** §§4–7, 8/PR0/PR3/PR4, 10. **Owner:** both.
**Depends on:** SG-001; correlate with SG-002 and SG-004.

- [x] Split restore/reconciliation into arena validation, filesystem hashing,
  watcher/cache reset, differencer work and ordinary initialization phases.
- [ ] Measure allocation, GC, residency and private-page growth per phase;
  explain the large anonymous charges observed before the first ordinary build.
- [x] Attribute retained observation tables and per-file hashing allocations,
  including the current fresh 64 KiB buffer for regular-file fingerprints.
- [ ] Measure the cost of frozen wrappers/records/readers and synchronized
  getters. Distinguish measured effects from hypotheses about monitor writes.
- [x] Compare all four initial arms under equivalent work and identify which
  costs are necessary, avoidable or still unexplained.

**Done when:** a phase-by-phase report explains the material excess or identifies
the remaining measurement gap, with ranked, testable fixes. Zero promotions
alone is not evidence that restoration preserves physical sharing.

## SG-006 — Correct only the costs established by measurement

**Plan:** §§4–7, 8/PR1–PR4. **Owner:** graph/runtime as appropriate.
**Depends on:** SG-002 through SG-005 for the selected change.

- [x] Select one measured cost at a time and record its expected removable
  bytes, semantic constraints and validation experiment before changing code.
- [x] Remove proven obsolete retention or unnecessary materialization; reduce
  fingerprint allocation or wrapper/read overhead only where evidence warrants.
- [x] Preserve misleading-metadata detection, canonical identity, exact
  concurrency contracts, native invalidation and generation/integrity checks.
- [x] Run the relevant existing regression suites and add cases for changed
  contracts or discovered failures; build `//src:bazel-dev` and pin its digest.
- [x] Repeat the affected diagnostic and controlled comparison. Retain failed
  attempts and reject changes whose extra costs erase their benefit.

**Done when:** each retained fix has attributed before/after evidence and semantic
validation. If no justified fix exists, record that result for SG-010; this ticket
does not authorize unrelated architectural redesign.

## SG-007 — Close interface, compatibility and evaluator coverage gaps

**Plan:** §§3–5, 8/PR1/PR2, 9, 12. **Owner:** graph.
**Depends on:** SG-001; revalidate affected cases after SG-006.

- [x] Map existing native/heap/mapped coverage for graph membership, batch
  contracts, interning, versions, dirty/error values and stored event emission.
  Add only missing cases; preserve equal-value dependency replacement semantics.
- [x] Exercise concurrent promotion/signaling, DIRTY/CHANGE/REWIND transitions,
  deletion/recreation, cycles, cancellation and interrupted invalidation. Preserve
  reproducible seeds and traces for randomized tests.
- [x] Audit pruning, target counting, dump and serialization consumers for
  concrete casts, destructive operations and hidden array reconstruction.
- [x] Audit source-version metadata against the pinned native implementation.
  Its completed incremental entries return null, which freeze preserves. Retain
  explicit rejection for non-null metadata; a future native configuration that
  supports it requires an exact completed-state adapter before activation.
- [x] Verify current-code evidence for `SkyframeTests`, relevant node/evaluator/
  invalidator suites and the seven integration targets below. Ordinary passing
  native tests alone do not establish layered-backend coverage.

Required integration targets under
`//src/test/java/com/google/devtools/build/lib/skyframe:` are
`SequencedSkyframeExecutorTest`, `SkyframeFilesystemIntegrationTest`,
`FilesystemValueCheckerTest`, `PackageFunctionTest`, `LateDiscoveredInputsTest`,
`OutputsInvalidationIntegrationTest` and `RewoundActionOutputCheckerTest`.
Use the eviction harness only with actual stale-baseline/eviction cases.

**Done when:** the coverage matrix links all required invariants to passing
tests or explicit unsupported configurations, and no supported path silently
loses semantics. Open compatibility obligations remain visible.

## SG-008 — Complete preparation, restore and lifetime validation

**Plan:** §§7, 8/PR4, 9, 11. **Owner:** both.
**Depends on:** SG-001 and applicable SG-007 contracts.

- [x] Audit exclusion of commands, evaluation, repository activity, graph cleanup
  and pending invalidation through preparation/publication. Verify dirty entries
  remain mutable and unsuccessful/in-flight/discarded-analysis states are rejected.
- [x] Exercise cancellation/partial failure during conversion and publication:
  restore original handles, remove unpublished temporary storage and preserve a
  usable graph before publication; remain blocked after a post-freeze failure.
- [ ] Cover first-command queries, lost watcher continuity, stale metadata caches,
  untracked/missing files, globs, directories, symlinks, external repositories and
  content changes with unchanged size/timestamps.
- [ ] Cover missing/changed generated outputs, output rewinding, environment and
  configuration changes, transient errors and external connection refresh.
  Preserve warmed lifecycle inputs and the evaluator version clock.
- [x] Compare two clones changing the same key differently against ordinary
  incremental and fresh native builds, including diagnostics and action/cache keys.
- [ ] Exercise corrupt/truncated/mismatched generations, retry after reconciliation
  failure, discard, shutdown/reset/clean with live readers, and generation lifetime.

**Done when:** all required resume and failure scenarios have current evidence,
ordinary commands cannot bypass reconciliation, and clones evolve independently.

## SG-009 — Complete the comparable scaling and lifetime experiment

**Plan:** §§8/PR0–PR4, 10. **Owner:** runtime, with graph review.
**Depends on:** SG-001 through SG-008 evidence relevant to the tested revision.

- [x] Run unmodified warmed, patched-off, layered-heap and mapped-dependency arms
  on the same protocol. Add the payload arm only if SG-012 is completed later.
- [x] Measure 1, 2, 4, 8 and the expected production clone count. Provide enough
  host capacity for unconstrained comparisons; report capped runs separately.
- [ ] Exercise no-op, leaf, BUILD/Starlark and high-fanout edits; related/unrelated
  branches; revert; errors/recovery; and repeated work across several GC cycles.
- [ ] Collect total resident host memory, peaks, lifetime growth, marginal clone
  memory, GC activity, faults and backing I/O. Include template conversion peak,
  snapshot size, retained preparation buffers and other memory overhead.
- [ ] Measure feature-disabled overhead against unmodified Bazel and establish
  whether its memory cost lies within measured noise. Separate intrusive
  verification from memory observations and disclose its resident-memory effects.
- [x] Repeat under representative sustained pressure, verify reclaim, allocator
  survival, useful refault and post-pressure correctness. Preserve killed-helper
  profiles as failures; post-kill memory is not a held-load saving.
- [x] Report absolute bytes and uncertainty from independent seeds, not repeated
  samples treated as independent runs. Keep older code/protocol results separate.

**Done when:** the declared matrix has a complete auditable result set, including
failures/exclusions, sufficient to evaluate every gate in SG-010. Missing capacity
or uncertainty is recorded as an unresolved gate, not evidence of success.

## SG-010 — Make the dependency-stage acceptance decision

**Plan:** §§8/PR0–PR4, 10, 12. **Owner:** both.
**Depends on:** SG-002 through SG-009.

- [x] Record PR0 status: repeatable controls and largest memory categories.
- [x] Record PR1/PR2 status: native cost within measured noise and required
  semantics preserved across native and layered implementations.
- [x] Record PR3 status: replaced arrays absent, arena bytes unchanged and
  real incremental work consuming less private memory; quantify a small edge
  opportunity explicitly if that is the result.
- [x] Record PR4 status: all required resume/lifecycle cases pass with
  independent clones and a verified host backing path.
- [x] Evaluate zero semantic mismatches and at least 25% lower total resident host
  memory, with physical sharing and savings surviving representative lifetime,
  useful work, GC and pressure. Speed and execution time are outside acceptance.
- [x] Publish a pass/fail/unresolved table and a proceed/refine/stop decision.
  If promotion or retained Java costs erase savings, stop at that result. Any
  revised target or new storage direction must be explicit and evidence-based.

**Done when:** a reviewed decision links every gate to evidence. The experiment
is successful only when the plan's correctness and host-memory gates pass;
closing this decision ticket with failure must not mark those gates complete.

## SG-011 — Decide whether a larger payload is justified

**Plan:** §§6, 8/PR5, 12. **Owner:** both.
**Depends on:** SG-002 and SG-010. **Status:** conditional; do not start an adapter.

- [ ] Use retained bytes, fanout and actual caller behavior to rank candidates.
  Treat inline file contents and nested sets as candidates, not assumed winners.
- [ ] Estimate net savings including indexes, adapters, decoded caches,
  caller-retained copies and continued Java identity requirements.
- [ ] Audit public type, equality, hashing, streaming, identity and mutation
  contracts; identify APIs that require concrete strings/arrays/materialization.
- [x] Record one selected family and why it is justified, or defer payload work.
  A failed earlier gate does not automatically authorize expansion; document
  the deliberate change to the experimental sequence before proceeding.

**Done when:** an evidence-backed decision selects a bounded payload project or
explicitly defers it. Deferral is consistent with the original dependency-first plan.

## SG-012 — Implement and validate the selected payload adapter

**Plan:** §§5–6, 8/PR5. **Owner:** graph, with runtime review.
**Depends on:** SG-011 selection; SG-013 design if nested sets are selected.
**Status:** conditional.

- [ ] Add one audited heap-or-mapped representation with validated format/type
  metadata and documented materialization boundaries; migrate its consumers.
- [ ] Preserve the selected type's semantics and use existing codecs only where
  they do not silently restore the complete old heap representation.
- [ ] Bound decoded/flattened caches by bytes, key them by generation and avoid
  a permanently retained Java wrapper for every arena record.
- [ ] Prove original storage is released and measure all replacement overhead
  using the SG-002/SG-003 method; add semantic and lifetime coverage.
- [ ] Repeat SG-009/SG-010 with the payload arm. Require total memory improvement
  after all costs, not just a smaller logical heap or additional mapped bytes.

**Done when:** the selected payload preserves semantics and demonstrates net
memory improvement, with the complete experiment's gates reevaluated.

## SG-013 — Preserve nested-set identities if that payload is selected

**Plan:** §6. **Owner:** graph. **Depends on:** SG-011 selecting nested sets.
**Status:** conditional subproject; otherwise deferred.

- [ ] Design an internal mapped body/common child cursor compatible with the
  sealed implementation; a mapped external subclass is not a drop-in replacement.
- [ ] Preserve Java wrapper identity for depset equality, shared DAG-body identity
  and leaf identity separately. Keep interner behavior explicit.
- [ ] Start with audited immutable leaves; defer artifact leaves until generating
  action information and mutation/identity rules are handled correctly.
- [ ] Cover traversal order, duplicate elimination, depth, empty/singleton cases,
  interruption and flattening. Apply generation-aware byte limits to caches.

**Done when:** the design and tests establish those contracts and SG-012 can
implement the adapter without a general artifact-identity redesign.

## SG-014 — Add private-memory limits and usable operational metrics

**Plan:** §§4, 8/PR6, 11. **Owner:** both.
**Depends on:** SG-002 accounting and SG-008 lifecycle contracts.

- [x] Define and expose mode, generation, promotion reason, mapped/heap/private
  bytes, cache bytes and fallback reason. Label estimates
  and unmeasured quantities; avoid per-record writes in immutable pages.
- [x] Add a coarse private-memory budget and schedule full fallback at a safe
  command boundary when exceeded. Include measured cache/materialization costs.
- [x] Preserve dirty/deleted validity state until the evaluator is safely detached;
  never erase markers or silently reuse baseline data to satisfy a budget.
- [x] Test the threshold during edits, errors and pressure; verify observability
  and correctness with ordinary templates available for fallback.

**Done when:** operators can identify private growth and fallback causes, and
budget enforcement cannot resurrect stale values or interrupt graph invariants.

## SG-015 — Complete failure handling, reader lifetime and retirement

**Plan:** §§7, 9, 11. **Owner:** both.
**Depends on:** SG-008 and SG-014 for budget-triggered cases.

- [ ] Stop baseline use on runtime invariant failure, cancel affected work and
  restart with an ordinary evaluator. Replay only where proven safe; otherwise
  return an explicit failure. Automatic replay is not a prerequisite for safety.
- [x] Verify compatibility/integrity rejection and failed reconciliation never
  admit ordinary builds or queries before successful retry/full fallback.
- [x] Coordinate graph, reader, VM and controller lifetime so reset/clean/shutdown
  and generation retirement cannot unmap, rewrite or truncate live backing files.
- [x] Renew baselines by publishing a new template and routing new clones to it;
  keep existing clones' generations alive. Restart with native Bazel or a fresh
  whole-VM restore rather than importing the hybrid arena into an unrelated JVM.
- [ ] Exercise rollback and injected failures before/after publication, during
  work and at retirement; verify temporary-storage cleanup and durable diagnostics.

**Done when:** failure and retirement tests demonstrate safe fallback, no stale
baseline reuse and no mappings invalidated while readers/clones still use them.

## SG-016 — Validate operational lifetime and run a gated canary

**Plan:** §§8/PR6, 10–11. **Owner:** both.
**Depends on:** accepted SG-010 gates for the candidate revision, SG-014, SG-015;
repeat acceptance after any selected payload change. **Status:** blocked on gates.

- [ ] Exercise representative VM lifetimes, branch divergence, repeated commands,
  GC, pressure, errors and fallback using the candidate operational controls.
- [ ] Reevaluate total memory, physical sharing and correctness after the controls'
  own memory overhead; verify savings persist and private growth is bounded.
- [ ] Progress from synthetic coverage to representative repositories, internal
  related-branch workloads and a small canary pool only after preceding gates pass.
- [ ] Define canary stop conditions and test routing back to retained ordinary
  templates. Expand only after lifetime and recovery evidence meets the gates.

**Done when:** a reviewed canary report demonstrates sustained benefit and tested
rollback. Until then the default remains native/off and rollout is incomplete.

## SG-017 — Keep plan status and evidence accurate

**Plan:** §§8–12. **Owner:** both. **Depends on:** update as each ticket closes.

- [x] Update [experiment status](docs/shared-graph/README.md),
  [measurement requirements](docs/shared-graph/measurement.md), lifecycle/storage
  docs and fork PR descriptions with explicit stage status and immutable evidence.
- [x] Label historical milestone descriptions and earlier benchmark protocols so
  statements such as "no restore barrier" cannot be mistaken for current behavior.
- [x] Maintain a requirement-to-ticket/test/artifact index, source/binary/image
  identities, reproduction commands, uncertainty and known unsupported modes.
- [x] Preserve negative results and capacity failures. Mark conditional payload
  work deferred when unselected and blocked rollout work incomplete.
- [x] Record the final success or stop decision without presenting completed
  implementation checklists as proof of memory savings.

**Done when:** the repository gives one consistent, source-backed account of
what the original plan required, what passed, what failed and what remains open.
