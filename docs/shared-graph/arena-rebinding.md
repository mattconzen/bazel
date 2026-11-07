# Prepared-generation arena rebinding

This opt-in L-03 mechanism changes the backing of the existing dependency arena;
it adds no payload family or standalone import format. The stock Firecracker
PMEM snapshot pilot establishes the bounded staging/device design separately.
This source change does not itself establish DAX or physical sharing.

`shared_graph rebind --shared_graph_generation=UUID
--shared_graph_rebind_arena=/absolute/file` is allowed only for a complete
PREPARED mapped generation, once. The filesystem must be verified read-only
using kernel per-mount flags and the target must be a regular file. The new
file must match the length and saved checksum of this JVM's fully validated
arena, including its manifest checksum field. A different self-consistent file
with the same generation/revision/length is rejected. The controller must exclude
all file/image writers and graph commands throughout staging and rebinding.

The store atomically replaces a `(path, read-only memory segment)` backing
record. Canonical Java keys, value/version handles, node IDs, dependency offsets
and retained cursors remain in the same warmed JVM. Existing cursors keep the
store and offsets, so they read identical bytes from the new backing. No complete
private dependency vector or second key/index table is constructed. Automatic
arenas keep any in-flight segment alive and permit the old segment to be
collected after readers finish. Retained readers still survive graph retirement.

The lifecycle keeps ordinary command admission closed, collects template garbage
and prefaults the new mapping before preparation becomes complete again. A
failure after the swap boundary leaves preparation incomplete and requires
discard; it cannot release the command barrier. Read-only/path/generation checks
that fail before that boundary leave the original prepared mapping intact.
Reconciliation rechecks the rebound filesystem's read-only proof before reading
arena bytes and before accepting either ordinary or clean-clone change evidence.

For the proposed PMEM integration, attach an empty read-only PMEM image before
boot but leave its filesystem unmounted. After ordinary arena preparation,
sync/pause the seed and stop every VMM thread, export the arena and populate
the unpublished PMEM filesystem in place. Preserve the device inode and exclude
all readers during staging. Resume the seed, flush probe-time guest block
buffers, mount read-only with DAX required, verify the exact arena bytes, then
invoke rebind. The final snapshot must include the same PMEM device/path and
the paired root/RAM/state. Publish and lease all backing until every VM/reader
is retired; private controls must use independent image inodes with identical
bytes and the same access coverage.

Tests cover retained direct/reverse/group readers, canonical key identity,
collectability of the old segment, immutable bytes under later promotion,
different valid checksums, exact generation, one rebind, lost read-only proof,
ordinary admission and injected post-swap failure. These are JVM/source tests,
not proof that a real DAX mapping survives GC, held pressure and host-cache
eviction/refault. Controller integration, old mapping/cache attribution, physical
page evidence and complete memory accounting remain required. Old root-disk
arena copies and all source/cache/staging memory must remain visible in the
ledger until actually released.

`shared_graph prefault --shared_graph_generation=UUID` is an intrusive full-access
diagnostic for the mapped arena in the matching complete PREPARED or READY
generation. It verifies integrity and rebound read-only filesystem proof, then
reads a byte from every mapped page in the JVM. It preserves the admission state
on success; a failed integrity/prefault operation blocks ordinary commands and
requires discard. This supplies explicit full access for the sharing/private-copy
mechanism control after host eviction. Record it as observer work and exclude it
from unperturbed useful-work memory comparisons. It has no speed target.
