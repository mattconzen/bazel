# Restore retention and remaining adapter overhead

This audit applies to binary source `25c5664fcb31d915e7e2927864a722a1f80e44ed`.
The subsequent documentation commits do not change that measured binary.

## Preparation and reconciliation

`SharedGraphLifecycle.prepare` captures observations twice under the controller's
filesystem/workload exclusion. It compares the captures to reject changing
inputs, publishes the arena and retains one observation list. The second list,
freeze candidates, replacement staging list, temporary key-ID map and structural
validation sets are construction locals. They are not retained generation-wide
maps. Earlier weak-reference/retainer diagnostics and semantic tests cover old
delegates/dependency storage; these source observations are not new physical
reclaim measurements.

The retained observation list supports full revalidation when clone continuity
cannot be trusted. Removing it requires a replacement trust/fallback design;
the clean-clone fast path alone does not make those observations obsolete.
Fingerprint I/O reuses a single 64 KiB buffer per pass. Reconciliation validates
the existing arena with a streamed digest rather than reconstructing its key
table, validation sets and mapping. Initial structural validation remains required.

For the declared controller-verified unchanged clone, reconciliation skips all
53,974 saved observations, verifies immutable roots/mount protections and the
arena, and records zero regular-file content bytes. The ordinary full-content
path remains mandatory when the controller withholds the assertion. Source
tests cover same-size/mtime edits, directory/listing distinctions, missing files,
symlinks, writable nested views, missing mount proof and failed admission.
The live generation fixture covers output tampering with preserved metadata,
native repair/fallback and independent old/new-generation siblings.

Required digest reads, canonical keys, Java values, observation fingerprints,
generation safety and transient publication buffers remain costs. No blanket
claim that all preparation/restore overhead has been removed is made.

## Measured residual overhead

The matched four-VM post-useful physical diagnostic reports mapped cohort VMM
PSS 120,357,888 bytes above native, including approximately 110,317,227 extra
private Java-heap bytes. These intrusive captures have substantial observer
effects and are separate from unperturbed lifetime means.

A later logical histogram from clone zero, after full action verification and
full GC, contains the following mapped adapter objects. It is not a physical
ownership or allocation-rate measurement.

| Object family | Count | Shallow bytes |
| --- | ---: | ---: |
| `MappedGraphStore.KeyList` | 187,375 | 5,996,000 |
| `MappedDoneDeps` | 187,375 | 5,996,000 |
| `FrozenNodeRecord` | 187,375 | 4,497,000 |
| `LayeredNodeEntry.Frozen` | 187,375 | 2,998,000 |

Current conversion eagerly retains direct/reverse view objects and a record per
frozen mapped node. A possible immutable per-node state holding owner, index,
value and version would move view creation to ordinary callers. At the observed
layout, replacing 104 shallow bytes with an estimated 24 would remove about
14,990,000 live shallow bytes per VM before new caller allocations and alignment
effects. That estimate requires an API/identity/lifetime audit and measurement;
it is **not selected or implemented** by this document.

The entire mapped histogram exceeds native by only 7,984,224 shallow bytes at
that later checkpoint. It cannot causally explain the earlier 110 MB private
heap difference. GC commitment, placement, intermediate objects and diagnostic
ordering prevent that inference. Retaining a shared array of all Java values
would also let a single old cursor pin otherwise obsolete values, so it is not
an acceptable shortcut.

The next retained candidate remains the explicit backing advice, guest reporting
and heap-release policies already recorded in [payload-audit.md](payload-audit.md),
tested against the same policies on native. Additional adapters remain deferred
until a net physical benefit beyond their replacement/observer costs is shown.

## Follow-up view experiment

The [predeclared mapped-view cleanup](mapped-view-cleanup.md) now replaces eager
views/records with per-node store/index/value/version state. Views are created
when requested, and count/value/version reads avoid them. Reachability checks
cover collectable views and values, alongside existing promotion, rollback,
rebinding and reader-retirement tests. Physical validation of the new binary
remains pending; all byte measurements above retain their older source identity.

## PMEM staging-cache check

The archived first block of the old/new mapped-view comparison records a
128 MiB PMEM device file in each mapped arm. Publication hashes populate all
32,768 host file-cache pages. The existing stopped-generation staging policy
then advises that same owned file; validated before/after residency bitmaps
report **134,217,728 bytes before and zero bytes after**, with stable file
identity, before any worker restore. Both arms also record temporary export
copies unlinked before resume. These records are preserved in the
[first-block raw bundle](https://github.com/mattconzen/bazel-shared-graph-bench/tree/shared-graph/envoy-scale-pressure/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/mapped-view-cleanup/comparison-block-00)
and its `pmem-staging-audit.json`; the original and extracted trial records are
byte-identical.

This rules out retaining the entire preparation-hash-populated PMEM cache into
restore in these two cases. Subtracting the arena size from the device's logical
size does not establish wasted resident cache. The active root/RAM advice does
not cover PMEM, and the external-support file ledger does not inventory this
owned PMEM file. Useful-window PMEM cache residency outside the selected arena
therefore remains unmeasured. The terminal selected-page sharing observer cannot
fill that gap. No additional cache policy is selected from this check, and it
does not qualify physical savings or complete the restore-overhead gate.
