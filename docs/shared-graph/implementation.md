# Shared graph implementation

Source baseline: `fda97de2b3b179ecdfa8af455fffde8d0b6b6422` (the initial
`mattconzen/bazel` master and the implementation plan's inspected revision).
All PRs target `github.com/mattconzen`; see the ownership rule in `AGENTS.md`.

## Storage interface milestone

`InMemoryGraphFactory` selects storage when the evaluator is constructed. The
executor builder passes the factory to every new evaluator, including resets.
The default retains the existing incremental/edgeless graph selection and pooled
interning flags. A factory must return a fresh graph and reject combinations
that it cannot support. This is an internal injection point, not a build flag
that swaps a live graph.

`DoneDeps` provides repeatable flat and grouped traversal of completed direct
edges. `HeapDoneDeps` reads the existing empty marker, singleton key, or
compressed array without copying that storage. Group views are read-only;
`materializeForEvaluation` explicitly creates mutable evaluation state. The
reader preserves canonical key identity, group order, and within-group order.
This change does not offload any storage or claim a memory improvement.

The legacy compressed accessor remains for compatibility and for the native
node's own state transitions. A future backend must override `getDoneDeps` and
may materialize at the legacy accessor, but ordinary traversal must not call it.
No per-node reader field or decoded-dependency cache has been added.

## Concrete consumer inventory

| Consumer | Dependency on native storage | Interface migration |
| --- | --- | --- |
| `TransitiveTargetCounter` | Inspected compressed array elements and group markers | Iterates `DoneDeps`; keeps the single-dependency fast path and two-pass indegree updates |
| `AbstractInMemoryMemoizingEvaluator.dumpDeps` | Decompressed every visited node | Streams group views from `DoneDeps` |
| `ChangePrunableNodesFinder` | Ignored nodes outside `IncrementalInMemoryNodeEntry` | Requests last-build dependencies and last-evaluated version through `InMemoryNodeEntry` |
| `DumpCommand` changed-value summary | Used concrete class to obtain evaluation version | Uses the interface version accessor, retaining the stored-edges check |
| `FrontierSerializer` | Cast nodes before clearing values or consolidating reverse edges | Calls explicit destructive operations through `InMemoryNodeEntry` |
| `SelectedEntrySerializer` | Skipped non-native entries when clearing direct edges | Calls the interface operation on completed entries that keep edges |
| `ReverseDepsUtility` / `KeyToConsolidate` | Manage native mutable storage and signaling | Intentionally remain native internals; future promotion must reuse them |
| `IncrementalInMemoryNodeEntry` / `NonIncrementalInMemoryNodeEntry` | Own native compressed representation | Intentionally retain the existing storage and state machine |
| `InMemoryGraphImpl` | Allocates native nodes and manages canonical interning | Default factory behavior remains intact; alternate graphs must preserve those contracts |

Destructive serializer methods explicitly throw by default for storage that does
not implement them. A backend must implement them or reject incompatible modes
before serialization starts; silent omission is not supported. Cleared values or
edges remain incompatible with subsequent incremental evaluation.

## Validation

The aggregate Skyframe suite passes on the unmodified baseline. On the interface
change it includes dependency-reader tests for empty/singleton/mixed groups,
repeatable traversal, canonical key identity, immutable views, iterator
exhaustion, and independent materialization. Factory tests exercise all four
combinations of edge retention and pooled interning, exact graph identity, and
null-result rejection.

The existing graph and evaluator test classes expose factory selection so later
backends can run the same contracts. The executor integration suite records
factory calls through a weak reference and verifies factory reuse across reset
without retaining discarded graphs.

The implementation plan additionally requires physical memory, performance,
whole-VM restoration, and retained-heap evidence. Semantic test results do not
establish those gates. This milestone has no frozen store, mapped arena,
preparation/resume barrier, or payload offloading. Those require subsequent
implementation and measurement; an unrelated JVM cannot import a snapshot arena.

## Reproduction

Use the pinned `.bazelversion` and repository JVM toolchains. Run:

```sh
bazel test //src/test/java/com/google/devtools/build/skyframe:SkyframeTests
bazel test //src/test/java/com/google/devtools/build/lib/buildtool:TransitiveTargetCounterTest
bazel test //src/test/java/com/google/devtools/build/lib/skyframe:SequencedSkyframeExecutorTest
bazel test //src/test/java/com/google/devtools/build/lib/skyframe/serialization/analysis:SelectedEntrySerializerTest
```

Run the seven filesystem/output targets listed in the plan as separate targets
under `//src/test/java/com/google/devtools/build/lib/skyframe:`:
`SkyframeFilesystemIntegrationTest`, `FilesystemValueCheckerTest`,
`PackageFunctionTest`, `LateDiscoveredInputsTest`,
`OutputsInvalidationIntegrationTest`, and `RewoundActionOutputCheckerTest`,
plus the executor target above.
