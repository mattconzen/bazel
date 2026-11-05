# Requirement and evidence index

Use this index with [TICKETS.md](../../TICKETS.md) and the
[experiment contract](experiment-contract.md). Passing semantic tests does not
close memory or rollout gates. The completed production/scaling comparison uses
source `bafaaeb157b649306a41727febc52fe239275ae5`; the later preparation guard
has separate source-test qualification. Historical diagnostics below identify
their earlier revisions explicitly.

The [published evidence](https://github.com/mattconzen/bazel-shared-graph-bench/tree/21b343bbb24bcf0e0c4adae35089b1f31f93968e/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-04-plan-obligations)
contains all 24 production/scaling cases, 6,712 passing correctness checks,
independent-seed lifetime statistics and reusable four-VM memory treemaps.
Mapped first-600-second VMM PSS remains 26.4–29.7% higher than native. In the
separate matched diagnostic, private guest file cache accounts for about 84%
of first-analysis PSS excess; clone-zero live Java object bytes remain close.
This points to restore verification/cache behavior, not a successful memory gate.

## Semantics and compatibility

| Original requirement | Implementation / existing coverage | Remaining evidence |
| --- | --- | --- |
| Native evaluator and graph factory; default native behavior | `InMemoryGraphFactory`, evaluator/executor construction; factory and executor tests; current-protocol disabled control | Three seed sets are descriptive, not fleet bounds |
| Stable handles, merged views, batch creation, deletion/recreation and interning | `LayeredNodeEntry`, `LayeredInMemoryGraph`; `LayeredInMemoryGraphTest`, `LayeredGraphContractTest` | Operational edit/error/pressure cases now pass under a disclosed unit cap; broader invariant-failure injection remains |
| Native evaluation semantics after freeze | `LayeredMemoizingEvaluatorTest`, `MappedMemoizingEvaluatorTest` inherit the ordinary suite | Maintain coverage after each semantic change |
| Group/version preservation, equal-value dependency replacement, concurrent promotion/signaling | Inherited evaluator/node suites; `LayeredInMemoryGraphTest` | Preserve randomized seeds/failure traces with results |
| Mapped integrity, no hidden traversal materialization, collectability and reader retirement | `MappedGraphStoreTest`; real Envoy weak-reference/retainer audit and hot materialization counts | Maintain representative retention coverage after changes |
| Preparation/query admission, content verification and retry | `SharedGraphLifecycleTest`; Firecracker first-query receipts | Complete supported scenario-to-test mapping, including failed publication and outputs |
| Native differencer and already-dirty entries preserved | `SharedGraphExecutorIntegrationTest`, parameterized over heap and mapped | Continue supported output/repository recovery coverage |
| Lifecycle module precomputed inputs preserved | `BlazeCommandDispatcher.RuntimeTests` regression | Revalidate if dispatcher behavior changes |
| Concrete traversal/pruning/serialization consumers | [Consumer inventory](implementation.md) | Keep destructive/incompatible modes explicit and audit hot materialization |

The pinned ordinary `IncrementalInMemoryNodeEntry` does not override
`NodeEntry.getMaxTransitiveSourceVersion()`; its supported completed nodes return
null through the native default. `LayeredNodeEntry.stageFreeze()` checks that
same contract and frozen reads return null. Thus the present null check does not
discard source-version metadata supported by that native class. If a non-null
incremental adapter is introduced later, the existing rejection must remain
until exact freeze/promotion preservation is implemented. Do not claim support
for a hypothetical non-null configuration from this audit.

## Current test execution

The following targets passed for the instrumented source. Raw logs are published
in the evidence bundle's `test-logs/` directory. Later guard qualification and
its preserved failed expectation are identified separately below.

- `//src/test/java/com/google/devtools/build/lib/skyframe:SharedGraphLifecycleTest`
- `//src/test/java/com/google/devtools/build/lib/skyframe:SharedGraphExecutorIntegrationTest`
- `//src/test/java/com/google/devtools/build/skyframe:SkyframeTests`
- `//src/test/java/com/google/devtools/build/lib/skyframe:SequencedSkyframeExecutorTest`
- `//src/test/java/com/google/devtools/build/lib/skyframe:SkyframeFilesystemIntegrationTest`
- `//src/test/java/com/google/devtools/build/lib/skyframe:FilesystemValueCheckerTest`
- `//src/test/java/com/google/devtools/build/lib/skyframe:PackageFunctionTest`
- `//src/test/java/com/google/devtools/build/lib/skyframe:LateDiscoveredInputsTest`
- `//src/test/java/com/google/devtools/build/lib/skyframe:OutputsInvalidationIntegrationTest`
- `//src/test/java/com/google/devtools/build/lib/skyframe:RewoundActionOutputCheckerTest`

The seven integration targets exercise their existing native configurations.
The aggregate Skyframe suite also runs the inherited layered/mapped evaluator
contracts. Do not describe the seven integration suites as each having run every
backend. A local initial attempt could not start the Bazel server under the
network sandbox; the successful runs used its required loopback access.

The executor reconciliation regression was subsequently parameterized over both
heap and mapped storage and passed in both configurations. Its log is
`/tmp/shared-graph-obligations-mapped-executor.log`.

## First measured correction

The two-clone diagnostic at source `2c9c47316e844323d52e686adb44634b1c881794`
completed reconciliation with zero filesystem changes and zero promoted IDs.
Clone zero's command thread allocated 1,502.68 MiB during filesystem verification
and 221.85 MiB during arena validation. Neither the seed nor initialization
window reported host memory-limit or OOM events. The run then failed in the new
host observer, before first ordinary work; preserve it as a failed diagnostic,
not a passing cohort or performance result.

The observer incorrectly equated the device reported in `/proc/maps` with
`stat.st_dev`. A paused clone showed the expected path/inode but different device
numbers on this host. The corrected observer verifies the mapped pathname's
actual device/inode together with the kernel-reported inode; a real paused-clone
check and cross-process CoW tests pass. This was an observer failure, not evidence
that Firecracker used an anonymous restore backend.

SG-006's first selected change is to reuse one 64 KiB scratch buffer per
verification pass, including recursive output traversal. Previously each
regular-file hash allocated another buffer. The allocation-phase result bounds
the opportunity but does not prove all 1,502.68 MiB came from those buffers.
Keep file identity, metadata and content checks identical, record file/byte counts,
and compare phase allocation and host CoW afterward. Leave the separately
measured arena-validation cost unchanged in this experiment.

The first offline MAT audit also exposed a compact-header sizing mismatch:
retained object sets were useful, but MAT's default object byte sizes did not
match every JVM class. Calibrate instance sizes against the same-JVM histogram,
validate array layout, and expose unknowns. The owned compact-header fixture
checks the result against `Instrumentation.getObjectSize`; uncalibrated MAT
totals cannot close a retained-byte gate.

## Memory and runtime obligations

| Ticket | Evidence being collected | Acceptance status |
| --- | --- | --- |
| SG-001 | Four-clone/600-second contract, source/image/runtime pins, fresh references | All twelve production and twelve scaling cases complete under one protocol |
| SG-002 | Exact joint retained sets and value-family retention with Eclipse MAT | Sixteen matched stages complete, with calibrated sizes/unknowns and class-only retainer paths |
| SG-003 | Real retainer audit plus promotion/materialization counters | Real Envoy audit reports zero survivors in all tracked replaced-storage families; useful-work residual arrays match native promotions |
| SG-004 | Common snapshot identity/offsets, resident file PTEs and direct guest-frame ownership joined to host PSS | Four-VM attributed captures complete; two-VM arena probe proves sharing is lost during reconciliation; complete-host accounting remains unresolved |
| SG-005 | Phase allocation, heap/GC, wall diagnostics and four-VM page-owner treemaps | About 84% of first-analysis PSS excess is assigned to private guest file cache; unknown ownership remains explicit |
| SG-006 | Two measured allocation corrections and controlled after-probes | Fingerprints fell from 1,502.68 to 41.96 MiB/clone; arena validation fell from 221.85 MiB to 86,720 bytes. Full content I/O remains |
| SG-009/SG-010 | Comparable native/off/heap/mapped lifetime, scaling and explicit gate table | All 24 cases pass 6,712 checks; mapped first-600-second VMM PSS is 26.4–29.7% higher. Total-host/fleet bounds are not established |
| SG-011–SG-013 | Payload decision and adapter only if justified | Deferred behind earlier decision |
| SG-014/SG-015 | Budget, fallback, retirement and operational failures | Integrated command-boundary budget and generation leases; five operational diagnostics pass 32 checks under their disclosed 16 GiB caps; broader invariant-failure coverage remains |
| SG-016 | Long-lived operational validation and canary | Blocked on acceptance and controls |

Offline retained sets for different categories/value families overlap. Report
the union measured once when a combined figure is needed. The retained set of
value objects is not a promise that callers can release those objects; audit
external references and concrete API requirements before selecting a payload.
Never turn retained bytes directly into a physical-page savings claim.

The snapshot observer uses device/inode/offset identity and resident file PTEs.
It excludes anonymous CoW and nonresident PTEs and exports no PFNs. It establishes
sharing for the observed snapshot-file pages, not attribution to guest arena
pages, nor total host memory. Guest arena RSS remains a separate measure.

Eclipse MAT runs entirely locally as an analysis dependency with an owned
extension; no upstream MAT source is modified. Its
[batch interface](https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/tasks/batch.html)
and the kernel's
[pagemap definitions](https://docs.kernel.org/admin-guide/mm/pagemap.html)
are the tool/API references. Analyzer receipts pin dump, extension and tool
hashes; reports omit workload strings and export class/size data only.

The opt-in `shared_graph prepare --shared_graph_verify_retention` implementation
tracks replaced native delegates, nonempty direct arrays, multi-element reverse
containers and old direct readers with weak references. After template GC it
reports surviving reference counts/classes and drops the diagnostic weak
references. Default preparation does not allocate these per-node audit objects.
Mapped tests prove both collection and detection of an intentionally retained
array. Heap, mapped, lifecycle and runtime suites passed in
`/tmp/shared-graph-obligations-retention-tests.log`; real Envoy audit evidence
is now available: all tracked old delegates, direct arrays, reverse containers and
old readers had zero survivors after template GC. This proves the audited storage
families are released, not complete host-memory savings.

## Preparation and failure scenario audit

| Scenario | Current implementation and evidence | Scope / remaining work |
| --- | --- | --- |
| Command/evaluation exclusion | Dispatcher command lock, immediately preceding successful analysis-only build, prepared admission barrier; lifecycle rejection tests | Offline controller also owns filesystem/repository activity through publication; arbitrary concurrent external writers are unsupported |
| Dirty/error/in-flight nodes | Freeze stages successful completed entries; inherited layered/mapped evaluator suites preserve mutable dirty/error entries | A successful native integration suite alone is not layered coverage |
| Before-publication interruption/failure | Freeze checks interruption before commit, stages replacements, rolls installed handles back on failure, deletes unpublished arena; failed-construction mapped test retains a usable mutable graph | No mid-commit interruption check; rollback remains necessary for runtime failures |
| After-freeze failure | Lifecycle becomes PREPARED before GC/prefault; ordinary commands remain blocked | Error-injection coverage of every allocation/publication boundary remains incomplete |
| Missing paths, directories/listings, symlinks, unchanged metadata | Lifecycle fingerprint tests, including separate state/listing cache keys and recursive generated output content | External repository paths use the same observation code; representative remote connection refresh is not independently demonstrated |
| First restored query / failed reconciliation | Real guest query rejection; failed reconciliation test permits retry/control/shutdown and blocks ordinary query | A wrong/corrupt generation must never be relabeled a successful restore |
| Generated outputs, rewinding, configuration, transient errors | Native FilesystemValueChecker/OutputsInvalidation/LateDiscoveredInputs/RewoundActionOutputChecker suites; native monitoring and differencer initialization retained | These seven integration suites are not all parameterized over heap/mapped; executor test covers both storage modes |
| Equal-value pruning/version clock | Executor reconciliation retains evaluator identity and unrelated dirty state; inherited evaluator contracts | Module runtime regression preserves warmed precomputed inputs |
| Corrupt/truncated/mismatched arena | MappedGraphStoreTest covers checksum, trailing/truncated data, offsets, group boundaries, key handles, duplicate reverse edges, generation/revision/key table mismatches | Validating the checksum saved in the warmed JVM also rejects a rewritten arena with a newly valid disk checksum |
| Reader retirement/reset | Mapped retained-reader test keeps direct and reverse readers useful after generation cleanup | Controller VM leases additionally prevent deletion of snapshot assets while a VMM process is alive |
| Runtime budget/fallback | Controller command-boundary policy calls full native evaluator discard, then verifies actions and archives | Real edit/error/pressure thresholds recover in the same JVM with fresh-native outputs under a disclosed unit cap; private VMM bytes are broader than graph bytes |

This audit records supported contracts and the remaining representative evidence.
It does not check every SG-008 item merely because related native suites passed.

## Second measured correction: restore verification

The two-clone corrected-buffer probe measured about 41.96 MiB of fingerprint
allocation per clone, versus 1,502.68 MiB in the earlier two-clone probe. Both
restored clones read 5,232,833,830 bytes across 23,362 regular files with one
65,536-byte scratch buffer. This diagnostic overlap does not support a wall-time
or CPU acceptance claim. Arena validation remained 221.85 MiB per clone.

`verifyBackingFile()` was reopening the arena through `openValidated()`, which
also creates another mapping and per-record boxed duplicate-detection sets. The
next isolated correction replaces only this repeated restore validation with a
streaming digest. Initial publication still performs complete structural/key-table
validation. Restore checks the length, on-disk digest and freshly computed full
content digest against the checksum saved in the warmed JVM. Changed bytes with
a newly valid disk checksum must still fail. Controller filesystem exclusion and
the snapshot-coupled, already-validated Java tables remain requirements.

Expected removable cost: most of the measured 221.85 MiB of validation allocation;
the new path uses one 64 KiB chunk and a small fixed header. Validate original
corruption/format/table tests, add changed manifest/body/truncated/trailing restore
cases, rebuild/pin the binary, then repeat the diagnostic before performance runs.
No retained-byte or host-memory benefit is assumed from allocation alone.


## Published attribution and first controlled probes

[The immutable evidence bundle](https://github.com/mattconzen/bazel-shared-graph-bench/tree/42e11e66c4c325f7b7c592bafb8cc433abffac28/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-04-plan-obligations)
contains sixteen matched diagnostic stages, calibrated category/family retained
sets with unknowns, JVM histogram/heap/NMT correlation, all source test logs,
failed attempts and completed buffer/retention and budget/fallback probes.

The matched baseline shows native live histogram bytes of 259.56 MiB and mapped
254.26 MiB after first work. Nearby diagnostic JVM PSS is 972.41 versus 1058.84
MiB; full GC/heap dumps perturb those figures, so they are not performance
comparisons. Native direct arrays retain 9.85 MiB and reverse lists 9.24 MiB
individually. Their union lies between the maximum and sum, before replacement
costs. These are modeled object bytes, not host-page savings. Mapped frozen
representation roots occupy 25.43 MiB including mapped dependency readers;
the heap category's 12.72 MiB excludes its separately categorized dependency
readers and therefore is not a directly comparable total.

After useful work the mapped arm has four small native direct arrays and three
reverse lists, six native promotions, 11,932 copied dependency IDs and 570
legacy materialized IDs versus approximately two million graph dependencies.
Class-only inbound paths lead to native entries. The real template weak-reference
audit separately reports zero survivors for 208,327 delegates, 144,813 direct
arrays, 12,008 reverse containers and 208,327 old readers. This distinguishes
legitimate promoted storage from the original offloaded arrays.

The two-clone probe passes 13 action/output/identity/integrity checks and confirms
398,352,384 shared resident snapshot-file bytes after restore using common
immutable backing identity/offsets and file PTEs. This is not arena-specific
attribution. The one-clone low-budget probe passes 12 checks: full native discard
at a safe boundary retains the JVM and verifies fresh-native actions/archives.
Both generation registries end retired with zero live leases.

The controller's extended workload adds a widely loaded Envoy Starlark edit,
two clones changing the same Starlark key differently, and an actual compiler
error followed by archive-verified recovery. Compiler error contracts strip ANSI
formatting and the optional GCC `#error` prefix, deduplicate PIC/non-PIC copies,
and reject extra error messages. The pinned Clang's actual output was reproduced
locally; all raw guest diagnostics/commands remain preserved. A superseded
reference protocol is retained separately rather than mixed into acceptance.


[The matched two-clone streaming-validation probe](https://github.com/mattconzen/bazel-shared-graph-bench/blob/f15ca6725771b4bf9e1284747fadf170d8f0441f/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-04-plan-obligations/stream-validation-probe/trial.json)
subsequently passed all 13 checks
with the same actor hash as its fresh native reference. Arena validation allocated
86,720 bytes per clone (about 84.7 KiB), compared with 221.85 MiB on the preceding
binary. It retained unchanged arena digests, ordinary action/archive equivalence
and safe generation retirement. The differently pinned earlier protocol-transition
probe is excluded from acceptance. Production CPU, latency and total-host memory
remain separate pending gates.


## Preparation failure-path regression

An additional audit found that phase-metric cleanup or metadata allocation could
throw after the graph committed its frozen baseline, before lifecycle admission
closed. The new guard reserves admission before freeze and reopens it after a
failed construction only when no baseline was committed. Post-freeze failure
keeps the generation blocked and requires full discard; an incomplete preparation
cannot be reconciled into READY. Successful reconciliation failures still permit
the existing retry path.

A failed preparation consumes its preceding-successful-build token. Ordinary
commands remain available before commit, but another preparation requires a new
successful analysis build rather than freezing after a potentially changing
filesystem capture. The construction-failure regression covers that recovery.

Heap/mapped post-freeze error injection and precommit construction-failure tests
pass. Additional heap/mapped cases inject failure after the first and
last handle commits and require complete rollback, removal of unpublished arena
storage and a successful retry. An executor test changes a `select()` configuration
after reconciliation and requires reanalysis while preserving the evaluator.
These regressions passed after the performance matrix, together with the
aggregate Skyframe, executor integration and runtime suites. The first attempt
exposed an old test expecting immediate retry after interruption; it now checks
the consumed admission token and performs fresh analysis before retrying.
The measured cohorts remain pinned to binary
`bafaaeb157b649306a41727febc52fe239275ae5`, which predates this admission guard;
no acceptance result is silently transferred to the later code.

The final status surface also exposes whole-JVM used/committed/maximum heap,
mapped legacy-materialization counts, and explicitly unattributed private-graph
and cache-byte fields. Arena extent, JVM heap and the controller's whole-VMM
private-resident budget have separate scopes; these fields must not be summed.

## Completed operational cases and stage decision

[Five two-VM cases and exact receipts](https://github.com/mattconzen/bazel-shared-graph-bench/tree/21b343bbb24bcf0e0c4adae35089b1f31f93968e/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-04-plan-obligations/operational-diagnostics/README.md)
pass 32 checks. Missing/corrupted generated outputs are rebuilt to fresh-native
hashes. Thresholds during edit, compiler error/recovery and held pressure each
confirm two native fallbacks with unchanged JVM identities and no pending PIDs.
All generations retire with zero leases, and arena digests remain unchanged.
Every 16 GiB diagnostic unit hits its memory cap (no OOM/swap), so none supplies
unconstrained memory/performance credit.

The arena probe first proves 31,076,352 common resident arena bytes, then zero
following reconciliation. Useful-work, GC, held pressure, cache eviction and
controlled pageout/refault do not restore sharing; witnessed resident arena
pages refault as private CoW. An independent uncapped four-VM capture observes
all four arenas evicted during reconciliation. Byte immutability is preserved,
physical sharing is not.

[The acceptance decision](https://github.com/mattconzen/bazel-shared-graph-bench/tree/21b343bbb24bcf0e0c4adae35089b1f31f93968e/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-04-plan-obligations/README.md#acceptance-decision)
is to refine restoration, keep defaults off, defer payloads and block canary.
VMM PSS is worse across all three production seed pairs. Full-host accounting,
overall CPU/fleet p95 bounds, some external-refresh/fault-injection scenarios and
payload fanout/materialization rankings remain open. The broadened graph-sharing
hypothesis is not disproved. The committed regression tool checks compatible
single-cohort captures and attribution coverage, not fleet acceptance.
