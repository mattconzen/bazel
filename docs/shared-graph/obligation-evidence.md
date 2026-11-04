# Requirement and evidence index

Use this index with [TICKETS.md](../../TICKETS.md) and the
[experiment contract](experiment-contract.md). Passing semantic tests does not
close memory or rollout gates. Status below starts at source revision
`2c9c47316e844323d52e686adb44634b1c881794`.

## Semantics and compatibility

| Original requirement | Implementation / existing coverage | Remaining evidence |
| --- | --- | --- |
| Native evaluator and graph factory; default native behavior | `InMemoryGraphFactory`, evaluator/executor construction; factory and executor tests | Current-protocol feature-disabled cost comparison |
| Stable handles, merged views, batch creation, deletion/recreation and interning | `LayeredNodeEntry`, `LayeredInMemoryGraph`; `LayeredInMemoryGraphTest`, `LayeredGraphContractTest` | Operational budget/fallback cases |
| Native evaluation semantics after freeze | `LayeredMemoizingEvaluatorTest`, `MappedMemoizingEvaluatorTest` inherit the ordinary suite | Maintain coverage after each semantic change |
| Group/version preservation, equal-value dependency replacement, concurrent promotion/signaling | Inherited evaluator/node suites; `LayeredInMemoryGraphTest` | Preserve randomized seeds/failure traces with results |
| Mapped integrity, no hidden traversal materialization, collectability and reader retirement | `MappedGraphStoreTest` | Real Envoy retained-array/retainer audit; synthetic collectability is insufficient |
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

The following targets passed for the instrumented source. Raw logs are retained
locally as `/tmp/shared-graph-obligations-phase-tests2.log` and
`/tmp/shared-graph-obligations-plan-suites.log`; publish copies with final receipts.

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
| SG-001 | Four-clone/600-second contract, source/image/runtime pins, fresh references | Current-protocol warmed controls still required |
| SG-002 | Exact joint retained sets and value-family retention with Eclipse MAT | Matched four-arm stages pending |
| SG-003 | Real retainer audit plus promotion/materialization counters | Absence of all claimed obsolete arrays not yet established |
| SG-004 | Common snapshot identity/offsets and resident file PTEs, VMM PSS and unit charges | Arena-specific durability and complete-host accounting unresolved |
| SG-005 | Per-phase command-thread allocation, heap use, GC and wall diagnostics | Filesystem verification 1,502.68 MiB and arena validation 221.85 MiB allocated in the pre-fix probe; corrected comparison pending |
| SG-006 | Only evidence-backed fixes | Bounded fingerprint buffer reuse selected; measured after-result pending |
| SG-009/SG-010 | Comparable native/off/heap/mapped production lifetime and explicit gate table | Prior corrected mapped memory results failed in VMM scope; current acceptance pending |
| SG-011–SG-013 | Payload decision and adapter only if justified | Deferred behind earlier decision |
| SG-014/SG-015 | Budget, fallback, retirement and operational failures | Explicit discard exists; remaining controls unfinished |
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
remains required before closing SG-003.
