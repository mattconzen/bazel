# Initial full-Envoy memory opportunity audit

This L-01/L-04 checkpoint records measured ownership before selecting another
payload. The backing qualification remains open. No new payload adapter is
justified by this document, and these diagnostic quantities are not a total-host
savings result.

## Scope and pins

Native Bazel revision `fda97de2b3b179ecdfa8af455fffde8d0b6b6422`, binary SHA-256
`aa352aa4a8009dc8f746acc0f1c53371ee3e249f28d1dd5b7ea0de4e959ce515`, builds
Envoy revision `5d3b0dcaf48f3102f8ae4aa25374037b3ae37a83` with the recorded
module-lock overlay, `-c opt`, two build jobs and a 4,096 MiB maximum Java heap.
Both full executable and repeat builds verify their successful BEP output,
actual executable bytes and actual `--version`. All native controls produce
executable SHA-256
`9e0aa0218ad8b704f6fbaba420ebaba1548afc06304d22d068cf55ecd2bfa019`.

The first full build reproduces a 12,581,642,240-byte owned peak and
11,661,754,368 settled bytes. Settled cgroup owners include approximately
2,968,383,488 anonymous, 8,499,335,168 file and 191,967,232 kernel bytes.
Compiler/linker processes have exited at that checkpoint; these totals cannot
assign the compilation/linking peak to individual processes. Prepared input
cache charged outside the unit remains unresolved. PSS and these charges
overlap and must not be added.

The separate post-repeat heap diagnostic uses OpenJDK 27+35, G1, compact object
headers, compressed references and eight-byte object alignment, all observed
through `VM.flags -all`. HPROF SHA-256 is
`34ffafd296f502f4992d87bbbb8e5a12a5fe674c1ef515d67163aea3b0d8d91d`.
The calibrated MAT analysis uses the same JVM's class histogram and validates
stable per-class totals. Uncalibrated objects and MAT-model bytes remain
explicit. The same-run histogram contains 415,205,664 live shallow bytes.

## Measured graph roots and families

Numbers below are calibrated **joint retained heap models**, not physical pages
or predicted reclaim. A retained object set measures ownership under this dump's
other roots. It does not establish that an adapter can remove those objects.
Rows overlap; do not sum them. The union of all audited graph root categories is
241,031,552 modeled bytes, including explicitly uncalibrated objects.

| Root set or family | Root objects | Joint retained modeled bytes | Uncalibrated MAT-model bytes in set |
| --- | ---: | ---: | ---: |
| All node values | 255,603 | 137,279,160 | 528 |
| Canonical keys | 329,642 | 23,309,744 | 48 |
| Native nonempty direct dependency arrays | 201,410 | 36,181,184 | 0 |
| Native reverse-dependency ArrayLists | 22,483 | 36,552,024 | 0 |
| RuleConfiguredTargetValue | 33,266 | 54,479,936 | 240 |
| PackageValue | 1,613 | 32,159,808 | 0 |
| AspectValueWithTransitivePackages | 697 | 5,945,440 | 0 |
| GlobsValue | 715 | 5,426,232 | 0 |
| DirectoryListingStateValue | 6,662 | 4,163,544 | 0 |

Canonical keys remain needed by ordinary callers and identity-sensitive APIs.
The dependency root sets are representation-specific and overlap keys/values;
even isolated array/list sets are not automatically removable bytes. A heap
histogram's `Object[]` total cannot identify dependency storage or a value family
without ownership analysis.

## Initial ranking and decision

1. **File cache and its provenance.** This owns the largest observed GB-scale
   settled category. Attribute individual source/toolchain/output/snapshot files,
   guest cache and outside charges before choosing a shared or discard policy.
   Read-only DAX file access could avoid per-guest cache replication but has
   kernel/device/page-table costs and could benefit stock native too. It is a
   separate opportunity from the current dependency format.
2. **Committed versus live JVM memory.** The observed committed heap and process
   residency substantially exceed the post-GC live heap. Measure remaining
   allocation/GC pages and native owners. A smaller live heap does not prove that
   the JVM returned resident pages or that clones preserve physical sharing.
3. **Existing dependency storage.** Finish the current arena's backing proof,
   release/source-copy accounting and net comparison before adding a family.
4. **Configured-target and package payloads.** These are the largest measured
   value families. Their initial 54,479,936/32,159,808-byte retained models bound
   a logical ownership opportunity for this workload only. Actions/providers,
   nested sets, Starlark values, canonical identities, equality/hash behavior,
   caller-held references and mutation/lifetime contracts require separate audits.
   Wrappers, indexes, encoded backing and decoded caches could erase the benefit.
5. **Smaller metadata families.** Directory/listing and file-state values may
   have simpler contracts, but their measured bytes currently support a smaller
   opportunity. Do not expand the format without a net estimate and API proof.

No second payload is selected. The next direction is to complete the existing
Bazel DAX sharing fixture and complete physical cache/JVM attribution, then
record a bounded candidate's removable bytes and full replacement costs. This
decision does not treat the current encoded arena size as a ceiling on possible
whole-VM improvements. It also does not infer GB of offloadable graph objects
from GB of total resident memory.

## Stock cache-discard controls

The warmed control uses `--noallow_analysis_cache_discard`. Separate native
controls use `--discard_analysis_cache`, and
`--discard_analysis_cache --nokeep_state_after_build`. Each runs the same full
target and repeat build from an independently reflinked completed output base,
with the same heap/jobs/source pins and verified executable. These controls do
not replace warmed native or patched-off in the primary VM comparison.

Post-repeat diagnostic histograms contain 415,903,992 live bytes for warmed
native, 413,329,728 for analysis discard and 98,622,232 for complete-state
discard. Cache history and committed/resident pages differ; these are single-run
logical-heap diagnostics. In particular, complete-state discard's lower live heap
does not demonstrate lower total resident memory. Any selected shared backend
must explain its incremental benefit relative to this simpler stock option.

Raw runs remain in `shared-graph-latest-native-warmed-01`, `-warmed-02`,
`-warmed-03`, `-discard-analysis-01`, `-discard-state-01` and
`shared-graph-latest-native-heap-audit-01` under the owned host cache. The
benchmark fork retains reusable producers/analyzers; bounded committed evidence
bundles retain exact run configurations and measured source bytes separately
from the large heap, disk and RAM images.
