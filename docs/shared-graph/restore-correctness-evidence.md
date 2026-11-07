# Restore filesystem regression coverage

This closes the named L-02 filesystem-detection regression subtask for source
`008c6c626f514f4cc8f9bed454eaf4092d96bffc`. It does not close memory parity,
restore-overhead attribution, larger workloads or overall L-02 acceptance.

The [Java receipt](validation/mapped-views-2026-11-07/receipt.json) pins the final
tested sources. Its production hashes match the clean binary build. Lifecycle
and executor tests run for heap/mapped state as applicable; the graph/runtime
suites also pass. The real three-GiB VM fixture uses the built binary and image
`b127bbbcf6542bad6defad86aad8d70fc9f707e03640f8a720d50745d0de854a`.

| Required regression | Evidence | Scope |
| --- | --- | --- |
| Size/mtime-preserving changes | `fingerprintsDetectContentWithIdenticalMetadata`; `reconciliationDetectsStarlarkEditWithPreservedSizeAndMtime`; live preserved-metadata output corruption | Content fingerprints and actual retained-executor invalidation; native output repair in a VM |
| Directory/glob changes | Listing/state fingerprint tests; `reconciliationDetectsGlobRemovalAndNewSubdirectory` | Retained executor discovers changes without manually injected invalidations |
| Symlink changes | Listing/missing-file/symlink fingerprint test; `reconciliationDetectsRetargetedStarlarkSymlink` | Symlinked Starlark inputs cause the expected dependency graph to be rebuilt |
| Missing/changed outputs | Live `new-repaired-output` and `old-repaired-missing-output` action/output comparisons | Deleted or corrupted warmed library output is rebuilt to fresh-native hashes |
| Untrusted restore history | Reconciliation discards filesystem-watcher history; assertion/admission unit tests; live missing-output case with clone proof withheld | Full revalidation or failed admission replaces stale watcher/clone assumptions |
| Independent sibling changes | Live rollback/current-branch and unchanged-old-sibling comparisons, including after clean/fallback | Same-generation branches and independently prepared generations retain native-equivalent results |
| Failed reconciliation remains closed | Live loss of arena read-only proof, rejected ordinary aquery, then verified native fallback | No ordinary query admission until the invalid generation is discarded |

The [candidate lifecycle evidence](https://github.com/mattconzen/bazel-shared-graph-bench/tree/106a000/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/mapped-view-cleanup/lifecycle-validation)
passes 407 checks, thirteen fresh-native comparisons, retained-image verification
and fourteen altered-receipt rejection controls. All 338 measured sources remain
unchanged; both generations retire after every reader exits. Observed guest OOM,
owned swap and owned-limit reclaim are zero. Its live-log inspection is recorded
as an extra intrusive observer. Raw-case bundling/portable replay is pending
completion of the separate backing fixture and remains an L-05 deliverable.

These are the enumerated regression scenarios, with analysis-level and VM-level
coverage stated separately. They do not establish correctness for every possible
Bazel program or qualify the backend's memory footprint.
