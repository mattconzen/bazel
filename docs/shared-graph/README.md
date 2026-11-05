# Shared graph experiment status

All changes and pull requests belong to `github.com/mattconzen/bazel`. External
components must be forked under `mattconzen` before changes are made. The source
baseline is `fda97de2b3b179ecdfa8af455fffde8d0b6b6422`.

| Plan stage | Current result | Remaining acceptance work |
| --- | --- | --- |
| PR 0: measurement | Sixteen retained-heap stages, all 24 production/scaling cases, calibrated sizes/unknowns and reusable direct VMM treemaps | Complete-host reconciliation and fleet bounds |
| PR 1: interfaces | Factory, dependency reader and consumer migrations implemented/tested; disabled control measured | Three seed sets do not establish a fleet confidence bound |
| PR 2: heap prototype | Stable handles, freeze/promotion and membership semantics pass inherited and seeded contracts; local process-bound generation leases | Production deployment/retirement failure coverage |
| PR 3: mapped vectors | Semantic/integrity/retention tests pass; real dependency storage is released | Current restore path fails the private-memory and durable-sharing objective |
| PR 4: snapshot lifecycle | Startup modes, barrier, content reconciliation, failure guard and controller integration implemented/tested | All 6,712 matrix checks pass; memory/fleet bounds and unsupported external-refresh cases remain |
| PR 5: payloads | Deferred | Choose only after retained-memory measurements |
| PR 6: operations | Soft command-boundary private-resident budget, real native fallback and generation lease tests pass | Edit/error/pressure threshold checks pass under a disclosed unit cap; remaining runtime-failure coverage, canary and rollout gates |

The normal Bazel server still uses the native graph. Experimental heap and mapped backends reuse the existing evaluator. Startup activation is experimental and requires the preparation and restore
barriers described in [the lifecycle integration](snapshot-lifecycle.md).
A successful source build or mapped-unit test is not a successful whole-VM
sharing experiment. No host memory saving is claimed.

Implementation and reproduction details:

- [Factory, reader and concrete-consumer inventory](implementation.md)
- [Heap baseline and promotion contracts](heap-baseline.md)
- [Mapped format, validation and lifetime](mapped-dependencies.md)
- [Measurement tooling and acceptance gates](measurement.md)
- [Firecracker preparation and reconciliation](snapshot-lifecycle.md)
- [Remaining obligations](../../TICKETS.md), [fixed experiment contract](experiment-contract.md)
  and [requirement/evidence index](obligation-evidence.md)

The [published measurement bundle](https://github.com/mattconzen/bazel-shared-graph-bench/tree/21b343bbb24bcf0e0c4adae35089b1f31f93968e/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-04-plan-obligations)
shows that real Envoy template GC releases every tracked old delegate/array/
reader in the mapped audit. Native dependency storage is a small logical-heap
opportunity after replacement overhead; this is not the original 25% host-memory
result. Reusing fingerprint buffers reduces restore allocation from about 1503
to 42 MiB per clone. A separately tested correction removes repeated structural
validation of an already-validated arena, retaining exact saved-checksum checks.
All twelve four-clone production cases and twelve scaling cases are complete.
Mapped VMM PSS is 26.4–29.7% higher over the first ten useful minutes. Direct
four-VM attribution assigns about 84% of first-analysis excess to private guest
filesystem cache; live Java object bytes remain close to native. The uncapped
capture also observes every mapped arena evicted during reconciliation, then
private refault after verification. The next investigation must preserve a
trustworthy filesystem difference while avoiding this restore/cache cost.
Payload adapters and canary rollout remain conditional; no broader hypothesis
has been disproved by this implementation-level failure.

The following smoke and generated-fixture results are historical protocols,
separate from the current Envoy attribution and acceptance comparison.

The controller fork ran mapped, heap and patched-native two-clone correctness smokes on Firecracker 1.17 / guest Linux 6.1.188. Each completed all 45 checks, including fresh native output/action comparisons, edit/error/recovery/GC phases and inherited daemon checks. Enabled backends blocked the first query and detected a size/mtime-preserving restore edit. Five Bazel suites and 15 focused controller tests passed. The controller records source/binary/runtime hashes and reproduction in its `01-snapshot/SHARED_GRAPH.md`. Runs overlapped and host PFNs are masked, so these results establish neither comparative performance nor physical sharing.

Initial memory/performance screening is complete in [controller PR 3](https://github.com/mattconzen/bazel-shared-graph-bench/pull/3): twelve sequential two-clone trials, three each of original native, patched-off, heap and mapped storage, using a 256-target generated fixture. All 1,164 correctness checks passed. Median VMM PSS was 705 MiB native, 693 MiB off, 723 MiB heap and 730 MiB mapped. Mapped showed 5.4% higher paired median PSS than patched-off; steady builds were similar, while restore initialization plus first build was 2.55 seconds versus 1.61 seconds. The arena was only 108 KiB and clones reported 719 promotions by the first query. These results do not establish the 25% memory gate; representative workload, backing-cache reconciliation, scale and pressure measurements remain open. Keep the feature disabled by default.
