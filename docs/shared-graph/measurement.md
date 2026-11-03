# Measurement tools and open acceptance gates

These tools collect evidence; they do not create VMs or establish memory savings.
The mapped graph remains an internal prototype until template preparation,
command admission and restore reconciliation are integrated with the selected
VM controller and branch materializer.

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
