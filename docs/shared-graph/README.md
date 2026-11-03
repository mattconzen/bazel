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
| PR 4: snapshot lifecycle | Not implemented | Selected VM controller/materializer, command admission, trustworthy filesystem reconciliation, atomic generation publication and clone resume tests |
| PR 5: payloads | Deferred | Choose only after retained-memory measurements |
| PR 6: operations | Not implemented | Safe fallback, memory limits, operational controls, canary and rollout gates |

The normal Bazel server still uses the native graph. Heap and mapped backends
are internal APIs tested through the existing evaluator. There is deliberately
no user-facing activation flag before preparation and restore barriers exist.
A successful source build or mapped-unit test is not a successful whole-VM
sharing experiment. No host memory saving is claimed.

Implementation and reproduction details:

- [Factory, reader and concrete-consumer inventory](implementation.md)
- [Heap baseline and promotion contracts](heap-baseline.md)
- [Mapped format, validation and lifetime](mapped-dependencies.md)
- [Measurement tooling and acceptance gates](measurement.md)
