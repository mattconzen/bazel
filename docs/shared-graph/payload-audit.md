# Initial full-Envoy memory opportunity audit

This L-01/L-04 checkpoint records measured ownership before selecting another
payload. Declared arena sharing is now proved through GC, pressure and host
backing refault; complete supporting-memory qualification remains open. No new payload adapter is
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

A later warmed forced-codegen diagnostic separately analyzes the full executable,
recompiles the version translation unit and performs the full static link. It
reproduces the expected executable and retains 360 actual process/cgroup samples.
Sampled concurrent PSS reaches 2,958,432,256 bytes for the server JVM,
3,071,634,432 for the linker and 673,811,456 for the compiler at different times.
At the largest linker sample, the overlapping unit charge is 13,573,373,952 bytes,
with 9,487,806,464 file bytes, 3,897,819,136 anon and 185,442,304 kernel bytes.
Do not add these sampled role maxima or sum them with cgroup charges. This
[retained phase diagnostic](https://github.com/mattconzen/bazel-shared-graph-bench/tree/0dfd594/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/native-phase-and-lifecycle-failures)
attributes the warmed recompile/link footprint. That diagnostic did not observe
cold whole-build compilation; the later guest admission below supplies sampled
cold-build coverage.

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

No second payload is selected. The next direction is to complete supporting
cache/JVM attribution and test the bounded policies below. This
decision does not treat the current encoded arena size as a ceiling on possible
whole-VM improvements. It also does not infer GB of offloadable graph objects
from GB of total resident memory.
The [initial value-family API audit](payload-api-audit.md) records concrete
configured-target/package consumers, clearing and caller-retention obligations,
the limits of the existing remote codec and the explicit deferral decision.

## Physical owner checkpoint after the selected policies

Matched four-VM ownership diagnostics, using guest reporting, heap release and
owned backing advice in both arms, now identify substantial remaining private
residency. These are post-useful diagnostic snapshots with large probe effects,
not the natural lifetime means or a retained-object model.

| Physical owner | Native private bytes, four VMs | Mapped private bytes, four VMs | Reclaim/offload question |
| --- | ---: | ---: | --- |
| Java heap mappings | 665,412,949 | 775,730,176 | Which live identities, collector pages and temporary/caller-held objects can actually be replaced or released? |
| Other JVM anonymous mappings | 707,762,859 | 672,133,120 | Distinguish live native allocations, allocator retention, stacks and collector structures before changing native-heap policy. |
| Guest file cache outside userspace mappings | 514,359,296 | 513,609,728 | Separate useful source/output cache from disposable repeated cache, including refault and supporting costs. |
| Guest allocator free pages | 498,667,520 | 554,446,848 | Determine which private free pages remain resident despite reporting; free guest bytes alone are not a physical saving. |
| Guest slab | 365,211,648 | 366,669,824 | Attribute objects and required kernel working set before introducing a cache policy. |
| Other/unattributed RAM | 311,099,392 | 293,642,240 | Improve attribution; do not treat unknown pages as reclaimable or absent. |

These sets rank the remaining measured private footprints, with actual
recoverable fractions still unknown; zero-to-owner-size is only an opportunity
envelope. They are a subset of VMM PSS, not additional costs to add to PSS or
cgroup charges. Supporting host memory remains outside this owner table. Native
and mapped cohort PSS are 3,497,595,904 and 3,617,953,792 bytes. The final probes
change PSS by approximately -216 MB and -233 MB respectively, making causal
claims from these snapshots inappropriate. Unknown ownership remains above the
default 5% regression guard, which correctly fails.

The [committed raw captures, comparison and PNGs](https://github.com/mattconzen/bazel-shared-graph-bench/tree/88f2df9/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/policy-memory-attribution)
permit replay. The [restore retention audit](restore-retention-audit.md) separately
estimates approximately 15 MB/VM of possible eager-view/record shallow overhead;
it does not establish a resident benefit or select a new adapter. Existing
configured-target/package retained models remain logical opportunities requiring
their own equality, identity, mutation and lifetime design. Current evidence
supports completing the fixed-work independent comparison and improving these
larger owner measurements before adding another payload family.

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

## Bounded controller/JVM candidate selected for screening

The actual Bazel/JVM DAX fixture now passes 425 checks and 14 captures. Two VMs
share 31,363,072 rounded resident arena bytes versus 62,726,144 with independent
backing, including JVM GC, held pressure, actual backing eviction and useful
refault. Full and portable replay pass. These intrusive checks prove the declared
mechanism, not a whole-VM saving or an incremental backend advantage.

In the separate fixed 42-operation ordinary-build screen, one-VM owned means
are 4,491,426,356 bytes for native, 2,533,904,261 for native with targeted
root/RAM backing-cache advice, and 2,715,576,420 for mapped PMEM with the same
advice. Final native file charge is approximately 2.85 GB without advice and
175 MB with it. These independent short templates are diagnostics, not paired
causal estimates or 600-second qualification. Native with the simpler policy
uses less memory than mapped in this screen.

The first four-VM ordinary screen completes the same workload with native mean
owned charge 7,781,932,366 bytes and mapped PMEM plus advice 6,888,651,917 bytes,
about 11.5% lower. The completed native-with-advice control averages
5,948,387,105 bytes, so mapped plus advice is about 15.8% higher than that simpler
control. Patched-off averages 7,836,656,249 bytes. All arms complete the same 42
operations, and none spans 600 useful seconds. End-of-trace native VMM PSS
is 5,998,228,480 bytes and the overlapping unit charge is 9,415,147,520 bytes;
its file component is 3,833,614,336 bytes. Do not sum these quantities. Guest
free RAM totals approximately 3.09 GB, which does not establish how many of
those pages are privately resident on the host.

The next bounded screening candidate combines these explicit policies:

1. **Owned backing-cache advice.** Continue the tested per-file policy for only
   private root disks and immutable RAM backing, including it in a separate
   native control. The one-VM screen identifies a GB-scale cache opportunity;
   the four-VM comparison must establish its effect at the primary clone count.
2. **Guest free-page reporting.** Enable stock Firecracker 1.17.0 reporting with
   a zero-size balloon, no OOM deflation and guest reporting order zero. The
   pinned kernel already has the required driver. Keep nominal RAM unchanged.
   The expected removable set is host-resident mappings of guest allocator free
   pages; its intersection with private residency is unmeasured, bounded above
   by guest free bytes, and must not be assumed to equal that upper bound.
3. **Return unused committed JVM heap.** Screen explicit
   `-XX:MinHeapFreeRatio=5`, `-XX:MaxHeapFreeRatio=10` and
   `-XX:-ShrinkHeapInSteps`, with the same maximum heap and GC family. In the
   one-VM mapped seed, 284,691,712 used heap bytes coexist with 805,306,368
   committed bytes. The simple 10%-free model suggests roughly 316 MB committed,
   potentially about 489 MB less per VM at that checkpoint, before collector
   constraints, fragmentation, native costs and later allocations. This is a
   committed-heap opportunity estimate, not recoverable physical bytes. Reporting
   must actually return released guest pages to reduce VMM residency.

The JVM policy is recorded here before implementation. Flag support and actual
settings, live data, output correctness, GC, pressure, refault and total memory
still require testing. There is no execution-time objective. Devices, queues,
reporting work, temporary allocations, snapshot backing and native heap remain
costs. The three expected removable sets overlap; never add their upper bounds.
No new decoded cache, payload wrapper or value materialization is introduced.

Run the same policy combination on unmodified native to isolate the shared
backend's incremental value. A controller/JVM result would satisfy only its
demonstrated memory scope and would not prove that graph offloading caused it.
The warmed-native primary and patched-off controls remain required, with exact
pins and identical completed useful work. Freeze the trace after these separate
screens; independent comparisons, outside-memory accounting and the final
25% decision remain open.

## Build-tool attribution replay

The benchmark fork's reusable `report_native_phase_inventory.py` now replays
the completed warmed recompile/full-link profile and both failed larger guest
captures from portable raw evidence. It identifies 632,698,880 bytes of sampled
Rust-compiler PSS in the warmed host fixture, previously grouped as `other`, and
keeps other Java tools, persistent Java compiler workers and unresolved drivers
separate from the Bazel JVM. These process totals do not identify graph objects
or recoverable shared payloads.

The warmed stream contains 206 exited-during-capture observations and a maximum
689,902,868 ns sample gap. Its maxima remain sampled lower bounds. The new
report preserves those gaps, per-role executable-hash coverage and simultaneous
contributors; it does not add maxima from different times or requalify the two
failed guest builds. Both Python runtimes reproduce all three inventories byte
for byte. The later 8 GiB-guest / 4 GiB-heap native admission now completes both
the full cold executable build and repeat, with the same golden digest. Its
[portable raw admission](https://github.com/mattconzen/bazel-shared-graph-bench/tree/shared-graph/envoy-scale-pressure/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-05-latest-memory/large-native-admission-03)
contains 4,100 samples: 4,087 full-build and thirteen repeat samples. Sampled
concurrent JVM PSS reaches 2,634,131,456 bytes, C/C++ compiler PSS 2,256,894,976
and linker PSS 2,596,096,000 at different times. The largest simultaneous
all-process PSS is 5,221,538,816 bytes. These are guest process quantities and
must not be added to each other or to the 15,318,822,912-byte whole-case host
owned peak. That peak also includes preparation and intrusive terminal export.

The cold stream retains 525 exited-during-capture observations and a maximum
3,804,045,405 ns gap. Its sampled maxima remain lower bounds; executable identity
is unavailable for six compiler observations and one linker observation.
The terminal pre-profile G1 heap has 2,027,945,984 committed / 1,743,031,296 used
logical bytes. A subsequent intrusive full-GC histogram totals 444,086,776 shallow
bytes; its 794,055,222-byte HPROF remains on the host with a pinned hash. These
different stages do not establish reclaimable resident bytes or select a value
adapter. Actual larger guest ownership/family attribution, matched cohorts and
complete-host accounting remain open.
