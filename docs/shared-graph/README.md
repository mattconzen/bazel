# Shared graph experiment status

All changes and pull requests belong to `github.com/mattconzen/bazel`. External
components must be forked under `mattconzen` before changes are made. The source
baseline is `fda97de2b3b179ecdfa8af455fffde8d0b6b6422`.

| Plan stage | Current result | Remaining acceptance work |
| --- | --- | --- |
| PR 0: measurement | Logical graph inventory and host PSS sampler implemented/tested | Representative retained-heap and warmed-clone baseline runs |
| PR 1: interfaces | Factory, dependency reader and consumer migrations implemented/tested | Feature-disabled performance comparison |
| PR 2: heap prototype | Stable handles, freeze/promotion and membership semantics pass inherited and seeded contracts | Deployment-level template coordination |
| PR 3: mapped vectors | Validated read-only direct/reverse vectors pass semantic, integrity, GC and reader-lifetime tests | Real-workload private-memory and physical-sharing gate |
| PR 4: snapshot lifecycle | Startup modes, preparation barrier, content reconciliation and existing Firecracker controller integration implemented | Two-clone correctness smokes pass; representative performance/pressure gates remain |
| PR 5: payloads | Deferred | Choose only after retained-memory measurements |
| PR 6: operations | Explicit native fallback implemented | Automatic replay, memory limits, canary and rollout gates |

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

The controller fork ran mapped, heap and patched-native two-clone correctness smokes on Firecracker 1.17 / guest Linux 6.1.188. Each completed all 45 checks, including fresh native output/action comparisons, edit/error/recovery/GC phases and inherited daemon checks. Enabled backends blocked the first query and detected a size/mtime-preserving restore edit. Five Bazel suites and 15 focused controller tests passed. The controller records source/binary/runtime hashes and reproduction in its `01-snapshot/SHARED_GRAPH.md`. Runs overlapped and host PFNs are masked, so these results establish neither comparative performance nor physical sharing.
