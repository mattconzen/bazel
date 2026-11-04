# Firecracker preparation and reconciliation

This integration targets the existing offline warmed-Bazel experiments, forked
privately at `mattconzen/bazel-shared-graph-bench` from `mattconzen/monorepo` revision
`fb33959`. It uses the ordinary Firecracker file-backed private snapshot loader;
no VMM or JVM patch is required.

## Server startup and commands

Select `--experimental_shared_graph_mode=off|heap|mapped` before the command.
The default is `off`. The native client parses and forwards the setting; changing
it restarts the server rather than swapping an existing graph. Enabled modes
require a persistent server, `--noidle_server_tasks`, incremental tracking,
retained state and retained analysis. Incompatible discard/batch options fail
before command initialization.

After a verified warm build, the controller holds exclusive ownership of the
input/output filesystem and all guest workload admission. It runs a successful
`build --nobuild` of representative targets immediately before:

```sh
bazel --noidle_server_tasks --experimental_shared_graph_mode=mapped \
  shared_graph prepare \
  --shared_graph_directory=/root/bazel-seed/arena \
  --shared_graph_revision=EXACT_40_CHARACTER_SOURCE_REVISION
```

Use the same startup options/output base for every command. Preparation requires
that immediately preceding successful analysis-only build. A failed build or an
intervening ordinary command removes eligibility. This initial deployment is a
trusted offline guest with no concurrent filesystem writers, repository network
work or graph-consuming idle tasks. This exclusion must extend from the analysis
build through disk sync and snapshot capture; two fingerprint captures detect
changes during preparation but cannot establish what an arbitrary external
writer did before the first capture.

The lifecycle captures SHA-256 observations of every tracked file-state key,
missing path, directory listing and symlink, including external repositories.
Completed action outputs are observed too, with recursive content checks for
tree outputs. It compares two captures, freezes completed nodes, runs template
GC, and reads the mapped arena by page to fault its intended resident pages in.
The prepared barrier then rejects ordinary commands, including queries, before
incremental-state decisions and command initialization can consume the graph.
Only `shared_graph` control operations and shutdown remain available.

`shared_graph status` reports mode, state, generation, arena path/size,
fingerprint count, reconciliation time/changed-node count and fallback reason.
The controller records the arena digest and pinned binary digest/revision, syncs
the guest disk, pauses the VM and captures matching state and RAM.

## Restore

Every clone starts with the barrier still prepared. The controller patches only
its private writable disk path, resumes the guest, materializes branch changes
under exclusion and calls:

```sh
bazel --noidle_server_tasks --experimental_shared_graph_mode=mapped \
  shared_graph reconcile --shared_graph_generation=EXPECTED_GENERATION_UUID
```

A wrong generation fails without releasing admission. Reconciliation resets only
filesystem watching, syscall caches and the digest cache; it does not call
`resetEvaluator`. It rehashes tracked contents/listings/symlinks/outputs, schedules
changed keys for deletion with their reverse closure, and uses the existing
empty-root evaluator path to complete deletion/invalidation. This conservatively
prevents equal metadata from pruning a content change away. Existing evaluator
versions continue. Subsequent commands perform their normal environment,
configuration, repository, output checking and error initialization.

A reconciliation failure leaves admission prepared for retry. An explicit
`shared_graph discard` replaces the whole evaluator with the native backend;
that server stays in fallback until restart. Reset and clean retire lifecycle
metadata; retained mapped readers keep their mappings alive through normal
store ownership and automatic arena lifetime.

The forked controller deliberately tries a query as the first restored Bazel
command and requires the barrier to reject it. `--restore-edit` additionally
changes source bytes before that query while preserving size and mtime. Its
ordinary useful-work phases still compare output/action results to fresh native
Bazel builds. PID/start-time checks establish that reconciliation retained the
inherited daemon.

## Publication and limits

The controller closes the seed VMM before hashing and protecting the disk, state
and RAM assets. It fsyncs them and atomically commits a manifest with their
lengths/digests, binary provenance and arena identity. The arena lives inside the
matching ext4 image. It validates the committed assets before cloning. The snapshot loader initially opens a separate writable bootstrap disk at its saved drive path; while paused, the controller switches to each private clone and verifies that the bootstrap was unchanged. The published canonical disk is never the writable bootstrap. Private
disk copies are explicitly writable; shared snapshot RAM uses the same immutable
host file and offsets. Published assets remain until every cohort VM is closed.
This is a run-scoped generation, not a reusable cross-run template registry.

Fingerprint tables are Java heap overhead; full content verification and template
GC add preparation/resume cost. Prefaulting does not guarantee sharing after
guest reclamation/refault. The integration smoke and existing semantic tests do
not establish fleet memory savings, production workload coverage, a private-memory
budget, automatic replay after runtime invariant failures, or canary readiness.
Those remain measurement and operational gates in the implementation plan.

Restore also revalidates the mapped arena against the checksum retained by the warmed JVM before reading dependency vectors. Rewriting both arena contents and its on-disk checksum does not bypass this comparison. Reconciliation initializes the ordinary incremental file monitor before flushing pending invalidations, retaining the existing evaluator and version clock.

## Reuse diagnostics and native invalidation

Reconciliation now submits changed observations to the recording differencer rather
than deleting their reverse closure. This preserves previous values for ordinary
change pruning and retains unrelated dirty nodes. Directory file state is type-only,
matching native `FileStateValue`; directory listings separately observe child names
and types. Observation caches include the observation kind, so these contracts cannot
reuse each other's fingerprints. Regular files still include content checks, including
equal-size/equal-mtime edits, and executed outputs remain observed.

Status includes up to 256 `changed_observations` entries with exact key, path,
observation kind and old/new SHA-256 fingerprints. These are diagnostics rather than
an exhaustive filesystem diff. Completed frozen nodes do not promote for null-parent
DONE checks, dirty-parent DONE checks, or consolidated reverse-edge reads on deletion.
These changes address measured reuse risks; they do not establish host memory savings.

`shared_graph status` also returns node/baseline counts and aggregate promotion
reasons, dependency IDs and copied mapped bytes. These counter reads do not scan
node handles and remain available while the generation is prepared. Full graph
dumps remain excluded by the prepared command barrier.
