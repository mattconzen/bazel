# Original-plan completion protocol

This protocol closes the evidence gaps tracked in [TICKETS.md](../../TICKETS.md).
It preserves the original dependency-first architecture and decision targets.

## Workload and decision

The user specified **four clones with a ten-minute lifetime**. Use related Envoy
branches sharing one binary/JVM/toolchain/configuration and guest path layout.
Each clone owns its writable workspace and outputs. Analyze
`//source/exe:envoy-static`; compile and compare archives for
`//source/common/version:version_lib`. This experiment measures graph reuse,
selected compilation and incremental work; it does not claim a complete Envoy
executable compile/link measurement.

The production comparison is four clones over at least 600 seconds after
reconciliation. Exercise no-ops, leaf/BUILD/Starlark edits, edit/revert,
errors/recovery, sibling independence, GC and guest pressure. Preserve all
correctness checks against fresh native outputs/action identities. Time actual
builds separately from verification and disclose between-round verification's
resident-memory effects. A ten-minute run is not ten minutes of idle residency.

The extended protocol changes `bazel/envoy_library.bzl` to exercise broad Starlark
invalidation and places two clones on different `poc_version.bzl` definitions
concurrently before reverting both. A deliberate compiler `#error` in the selected
library must match a fresh native error contract and then recover to identical
archives. These add two fresh native input states to the five existing controls.
The representative held load is 512 MiB per guest; record actual reclaim and
allocator survival, not the requested allocation alone. The historical failed
768 MiB stress profiles remain separate evidence. Explicit guest-cache eviction
is a separate refault probe and never substitutes for natural pressure.

Compare unmodified warmed native, patched-off, heap baseline and mapped
dependencies. Cold JVMs are context only. Use three independent seeds per arm
for the production comparison, counterbalancing arm order across seeds. Report
per-seed data and descriptive ranges; bootstrap confidence intervals over seeds
are exploratory with only three seeds. Never treat within-seed samples as
independent seeds. Report p95 within each seed without calling it a robust fleet
tail estimate. Add seeds if uncertainty prevents a decision.

The primary CPU/build and client p95 population uses the fixed named analysis
rounds. Faster arms may execute more filler no-ops to reach 600 seconds; exclude
those different filler populations from paired CPU/p95 comparisons. Report both
the actual full lifetime and the covered first-600-second memory integral, including
the first-sample delay. Verification and selected-library execution remain outside
timed analysis windows and can affect residency between rounds.

For 1/2/8-clone scaling, first perform one independent seed per arm as descriptive
screening. Repeat where a scaling conclusion is needed. Keep the earlier
1/2/4/8 results as a different code/protocol, and preserve constrained observations
as capacity results. Eight-clone comparisons must have sufficient unconstrained
capacity; do not credit smaller post-OOM footprints as savings.

Success requires zero semantic mismatches, proof that claimed offloaded arrays
are gone, unchanged arena contents, at least 25% lower total resident host memory
against unmodified warmed native, at most 5% extra CPU/build and 10% p95 latency
regression, with savings surviving the declared lifetime/GC/pressure sequence.
These are acceptance targets, not predicted outcomes. Diagnostic completion or
a documented failed gate does not make the experiment successful. No target has
been relaxed.

## Pins and environment

Original native source: `fda97de2b3b179ecdfa8af455fffde8d0b6b6422`.
Native binary SHA-256:
`aa352aa4a8009dc8f746acc0f1c53371ee3e249f28d1dd5b7ea0de4e959ce515`.
Envoy source: `5d3b0dcaf48f3102f8ae4aa25374037b3ae37a83`, owned fork
`mattconzen/envoy`, with the existing pinned module lock overlay.
Prior corrected patched code: `8ff24041b3b56434884157474972ce4d30510bed`;
binary SHA-256:
`24788fba86886d3b8a2d140c14b161a2add751ca8bbcb432c66fe4f5586ac040`.
Prior image SHA-256:
`f3279717669bee194de0c7e5994e4852e99a4a92400bae0dcdfe72a4ce4a8562`.

The prior image's `image.json` pins all source overlays and native libraries.
New code requires a new binary/image identity and fresh references for that
exact actor/image/runtime. Record actor source hashes and observed guest JVM
arguments/version, collector, compiler, kernel, Firecracker, huge-page policy,
host limits, machine configuration and snapshot backing device/inode/offsets.
Do not infer an observed JVM identity from a configured path alone.

Keep two vCPUs, 2 GiB guest RAM, a 768 MiB Java heap and one build slot for the
first comparable run. KSM remains off. Cohorts use fresh dedicated host cgroups
with swap disabled; record limit events. Do not change host swap, sysctls, KSM
or cache policy. Snapshot RAM/state are shared immutable backing; clone disks
are independent reflinks. Seed execution is warmed before the final successful
analysis-only preparation boundary.

## Evidence sequence

1. Inventory coverage and preserve existing receipts under their original pins.
2. Analyze existing HPROFs to validate tooling, then collect matched native/off/
   heap/mapped diagnostic stages. Full-GC heap dumps, dominator analysis and
   complete graph scans are diagnostic evidence, excluded from performance.
3. Measure retained sets, old-array retainers, committed/resident JVM memory,
   restore allocations and host sharing. Class histograms are shallow sizes;
   arena RSS is guest residency; neither proves durable host physical sharing.
4. Implement one measured correction at a time, retaining integrity and native
   evaluator contracts; validate before constructing a new comparison image.
5. Run the production/scaling protocol, record pass/fail/unresolved gates, and
   make an explicit proceed/refine/stop decision. Payload work requires its own
   retained-byte justification. Canary rollout requires passing gates.

Aggregate PSS, dedicated-unit charges and backing cache have overlapping scopes.
Account for all participating processes, kernel charges and unrepresented
backing cache without summing overlapping quantities. Masked PFNs are an
attribution limitation, not proof of absent or present sharing. If host sharing
or total-host accounting remains unmeasured, leave the relevant gate unresolved.

Graph semantics and runtime measurement are separate review roles. Both remain
the implementing agent's responsibility in this run; there is no claim of
independent sign-off until a separate review has actually occurred. All source,
controller and additional component changes/PRs stay under `github.com/mattconzen`.
