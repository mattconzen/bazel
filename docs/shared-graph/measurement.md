# Measurement tools and open acceptance gates

The local samplers collect process evidence; the forked Firecracker harness
creates VM cohorts. Neither tool establishes memory savings by itself.
The mapped graph is an experimental prototype with explicit preparation,
command admission and restore reconciliation in the forked Firecracker harness.
Physical-memory acceptance remains conditional on representative runs.

## Graph inventory

Run `bazel dump --skyframe=storage` on the patched server for JSON containing node
states, completed direct dependency/group counts, heap dependency-array counts
and reference slots, retained value-node counts by Java class, arena size,
materialization and promotion counters. The inventory runs on demand. It neither
consolidates reverse edges nor promotes nodes, decodes vectors or caches arrays.
There is no extra per-node instrumentation field in the default backend.

Heap reference slots include group-marker slots. They are not heap bytes: object
headers, reference widths, alignment, shared arrays, keys, handles, values and
collector costs require separate measurements. Value-class counts rank node
frequency, not retained value size. Use retained-heap profiling and JVM committed/
resident memory before choosing a payload family. Completed-edge counts omit
mutable in-progress dependencies, and dirty nodes may retain old values.

## Host PSS sampling

`scripts/shared_graph/measure.py` samples explicit participating host PIDs through
Linux `/proc/PID/smaps_rollup`. It records process start ticks and host boot ID so
PID reuse or a vanished process cannot silently turn into a lower memory total.
Incomplete samples have no aggregate and cause a nonzero result. RSS remains a
diagnostic metric; PSS is the primary apportioned resident-memory measurement.
The sampler refuses duplicate PIDs and never overwrites an existing output file.

Run it around the workload driver, supplying all VMMs and required host helpers:

```sh
python3 scripts/shared_graph/measure.py \
  --pid "$vm1_pid" --pid "$vm2_pid" \
  --arm mapped --revision "$bazel_revision" \
  --workload leaf-edit --restore-backend file-backed-private \
  --clone-count 2 --output /tmp/shared-graph-trial-01.jsonl \
  -- ./run-clone-workload leaf-edit
```

For a fixed observation window, replace the command with `--duration 30`.
Workload exit status is preserved. Collection failure is exit code 3 if the
workload itself succeeded. Child termination on sampler interruption only affects
the workload process the sampler created. Sampling the driver PID alone is not
sampling the VMM cohort.

Each file includes arm, revision, workload, clone count, restore backend, kernel,
KSM state, raw per-process measurements and elapsed/wall-clock timestamps.
Record snapshot/backing-file identity, exact binary/JVM/collector flags, VM
resources, toolchains, guest paths, huge-page policy and cache warmth alongside
it. The script does not measure unrepresented backing-file cache, cgroup kernel
memory, guest GC or build actions. Its metadata labels backing-file cache
unmeasured. Add that accounting without double-counting mapped pages before
claiming total fleet memory savings.

### Current Envoy lifetime sampler

The Envoy controller uses
[`cohort_metrics.memory_snapshot`](https://github.com/mattconzen/bazel-shared-graph-bench/blob/f15ca6725771b4bf9e1284747fadf170d8f0441f/apps/firecracker-bench/pocs/bazel-memory/common/cohort_metrics.py)
for the explicitly launched clone VMM PIDs. It reads the host's
`/proc/PID/smaps_rollup`, converts `Pss` from kernel kB to bytes using 1024, and
sums those VMMs. Start-time checks bracket each collection. The observations
are sequential, not an atomic snapshot; collection duration is recorded.

Linux apportions a resident page among its mappings. With four sole mappers,
one shared 4 KiB page contributes about 1 KiB per VMM and 4 KiB to their sum;
four private copies contribute 16 KiB to the sum. This is kernel PSS accounting,
not an estimate from arena size or guest heap use. See the
[kernel's PSS definitions](https://docs.kernel.org/filesystems/proc.html).

The lifetime sampler collects approximately once per second after reconciliation.
The ten-minute figure integrates the aggregate PSS curve with trapezoids and
interpolates the 600-second edge, dividing by the actually covered time. Initial
sampling delay and actual full duration remain in the receipt. The separate first
analysis point precedes its deferred action-query verification; the lifetime curve
includes verification between timed commands.

VMM PSS includes resident guest RAM (JVM, guest kernel, guest processes and guest
cache) and Firecracker userspace overhead. Guest JVM PSS is not added separately.
Host cache without resident VMM mappings, host kernel allocations and external
controllers are outside this sum. Dedicated-unit `memory.current` is recorded as
another, overlapping scope: it includes charges for the unit's processes, kernel
memory and file cache, subject to cgroup ownership. These numbers must not be
added together or relabeled as complete host memory. Page attribution to the
arena requires the separate diagnostic; PSS alone cannot identify which guest
objects occupy shared pages.

### Reusable physical-memory treemaps

The controller's [memory-attribution tool](https://github.com/mattconzen/bazel-shared-graph-bench/blob/21b343bbb24bcf0e0c4adae35089b1f31f93968e/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/MEMORY_ATTRIBUTION.md)
collects host VMM mappings and resident page-table flags, plus a deduplicated
guest physical-frame ownership ledger. It stops JVM threads during guest
inspection and pauses all VMMs during host inspection. It separates Java heap,
frozen arena, other guest userspace, filesystem cache, slab, page tables, free
frames and unknown ownership, with private versus snapshot-file backing.
Every physical partition must reconcile to kernel PSS; unknowns stay visible.

Compressed page ledgers and their hashes permit regeneration of PNG/SVG/PDF
treemaps. A separate JVM histogram supplies post-GC shallow object bytes, never
added to physical PSS. The regression CLI requires compatible complete captures
and explicit memory-growth and unknown-ownership tolerances. It rejects missing
VMs, changed evidence, limit-constrained captures and inconsistent partitions.
One matched diagnostic pair is a regression screen, not a fleet acceptance test.
[Published four-VM captures and treemaps](https://github.com/mattconzen/bazel-shared-graph-bench/tree/21b343bbb24bcf0e0c4adae35089b1f31f93968e/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-04-plan-obligations/memory-attribution)
pass correctness and kernel-PSS reconciliation. Regeneration from copied metadata
reproduces attribution and all output hashes without VM assets. Private guest
file cache accounts for about 84% of their first-analysis PSS difference; live
post-GC object bytes remain close. Unknown ownership is 8.73% for mapped at
first analysis, so a 5% coverage screen refuses that stage. After-reconciliation
and post-verification mapped stages have 2.69% and 3.21% unknown ownership and
support that stricter screen. Diagnostic capture effects and scope remain
separate from production performance results.

## Required experiment

Compare unmodified warmed snapshot clones, patched feature-disabled servers,
layered heap and mapped dependencies. Cold JVMs are context, not the control.
Run 1, 2, 4, 8 and the intended production clone count, using the same immutable
host snapshot backing and offsets. Include no-op and leaf edits, BUILD/Starlark
and high-fanout edits, related/unrelated branches, edit/revert, errors/recovery,
repeated commands/GC and guest memory pressure/refaulting. Begin with KSM off.

Collect raw repeated trials, absolute bytes and uncertainty. Check output,
action/cache-key, query, dependency, exit-status and diagnostic equivalence
against both ordinary incremental and fresh builds. Record template conversion
time/peak, retained heap, committed/resident heap, backing-file I/O, faults,
CPU/GC, throughput, p95 latency, bytes promoted versus bytes recomputed and
long-lived divergence. The initial gates remain zero semantic mismatches, no
retained arrays claimed as offloaded, unchanged arena bytes, at least 25% lower
fleet resident memory, at most 5% CPU overhead and 10% p95 latency regression,
with savings surviving realistic VM lifetime and memory pressure.

No physical-memory, fleet-performance, whole-VM resume or rollout gate has been
claimed by the current storage tests. Payload selection and rollout remain
conditional on these measurements.

## Tool validation

```sh
python3 -m unittest discover -s scripts/shared_graph -p 'test_*.py'
bazel test //src/test/java/com/google/devtools/build/skyframe:SkyframeTests
```

The sampler has parsing, unit conversion, incomplete-process and PID-reuse tests.
A live-process smoke check verifies nonzero PSS and propagation of a failing
workload's exit status. Graph inventory tests cover native, heap and mapped
storage without triggering materialization or promotion.

## Corrected Envoy restore experiment

The `8ff24041b3b56434884157474972ce4d30510bed` binary preserves module
precomputed inputs across lifecycle commands, uses native invalidation instead
of deleting the reverse closure, and avoids several unnecessary promotions.
With execution warmed before the final analysis-only freeze, unchanged restore
now reports zero filesystem changes and zero promotions. Through the first
version-library build, copied encoded dependency bytes fell from 20,611,560 to
47,888 in the separate diagnostic; Java direct-dependency arrays after work fell
from 138,517 to four. These are logical counts, not host memory savings.

Eight sequential cohorts compared patched-off and mapped storage at two and
four clones, with two independent seeds per cell. All 1,440 action/output and
identity checks passed. All steady windows were free of host memory-limit
and swap events. Median cohort VMM PSS across seeds was 3,451.8 versus 3,855.9
MiB at two clones, and 5,816.9 versus 7,490.2 MiB at four clones: mapped used
11.7% and 28.8% more in this scope. No-op analysis builds were timed separately
from action-query verification; verification still ran between rounds, so the
resident-memory comparison describes that verification-heavy workload.

A substantial cost is already visible before ordinary work: after restoration
and reconciliation, median dedicated-unit anonymous charges were 33.5 MiB for
the four-clone off control and 5,873.0 MiB for mapped. This locates a major cost
in the restore/reconcile interval despite zero dependency promotions. It does
not attribute those bytes to a particular allocation or page-write site, and
these overlapping memory measures must not be added to PSS.

The workload covers full `envoy-static` analysis/action identities and actual
version-library builds. The 128/256 MiB allocations were sustained, but none of
the 16 profiles witnessed reclamation in every guest. Two fresh two-clone
cohorts then sustained 512 MiB per guest with reclamation in both guests. Neither
arm sustained the full 768 MiB profile: guest OOM killed one off-control pressure
helper and both mapped helpers. All 230 recovery/correctness checks still passed
with inherited Bazel servers; post-kill memory is not a held-load saving.

This does not establish the plan's complete-host, physical-sharing,
retained-byte or CPU/p95 rollout gates. Keep the backend off by default. Detailed
provenance and pressure/recovery evidence are in the
[controller results](https://github.com/mattconzen/bazel-shared-graph-bench/blob/69788d7d394521195b47e0811832b84bee27dc81/apps/firecracker-bench/pocs/bazel-memory/01-snapshot/benchmark-results/2026-11-04-envoy-reuse/README.md).
