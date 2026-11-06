# Controller-verified unchanged filesystem clones

This is a separate, explicit L-02 trust policy. It is not enabled by immutable
input roots, a matching filename, a read-only mapping, timestamps, or matching
file sizes. It must remain off for edited or unproven restores.

The proposed controller must sync and pause a quiescent prepared seed, snapshot
its whole JVM/RAM, stop it, and publish the matching root disk and RAM/state
identities together. A protected staging directory excludes other writers.
The controller then uses Linux `FICLONE` to create a complete independent root
disk from the immutable published source. It records the actual successful
ioctl, source/destination identities, length and source publication digest.
The saved root is opened only by a paused VMM before its private disk override;
the controller checks that source identity/mtime/length and destination identity
did not change. No guest or host input/output writer may run between the clone
and reconciliation. The paired RAM/state assets and generation must match.
Any missing receipt, prior edit, replacement, unknown writer or discontinuity
withholds the assertion and requires ordinary full-content reconciliation or
discard. A cheap destination stat alone cannot establish continuity.

Linux documents whole-file clone atomicity and write isolation in the
[FICLONE interface](https://man7.org/linux/man-pages/man2/ioctl_ficlone.2.html).
This gives a consistent filesystem-image clone; it does not prove JVM pairing
or exclude later writes. Those remain explicit trusted-controller obligations.
This initial offline lab has no external guest networking or arbitrary tenant
work before reconciliation. The contract does not extend to an arbitrary VM,
network filesystem, externally writable disk, or untracked block overlay.

At preparation `--shared_graph_verified_clean_clone` permits this policy for
that exact prepared generation. At reconciliation the same flag asserts that
the controller has established all of the above. Without both, the full mutable
content path remains mandatory. The ordinary command barrier, exact generation
check, immutable-root proof, arena checksum validation, watcher reset,
transient-error invalidation and post-restore native invalidation machinery
remain in force. The optimization supplies an empty filesystem change set only
for a proven unchanged clone. It never proves that an edited clone is unchanged.

Status records the enabled policy and actual reconciliation evidence. The
content-read metrics record zero bytes and the number of observations justified
by clone continuity; these are not resident-memory or physical-sharing results.
Java tests cover permission at preparation, generation mismatch, withheld
assertions, immutable-root proof loss and ordinary edits after reconciliation.

The source implementation is experimental. The controller receipt integration,
fresh native comparisons and matched physical-memory qualification must pass
before claiming a repaired restore or any memory saving. Preparation still
verifies mutable content; this policy targets per-clone restore reads.
