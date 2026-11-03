# Snapshot-coupled mapped dependencies

`LayeredInMemoryGraph.freezeMappedAtQuiescentBoundary(directory, bazelRevision)`
uses the same caller-exclusion and one-generation contract as heap freezing.
Only successful completed nodes are converted. Values, wrapped event metadata,
versions, stable handles and canonical keys remain Java objects in the warmed JVM.
The arena is not an independently loadable graph cache.

The writer stages both grouped direct vectors and consolidated reverse vectors
as integer IDs into a retained canonical-key table. Construction maps, buffers,
old delegates and old dependency arrays are not stored in the published baseline.
Frozen node records hold only their retained Java metadata and mapped readers.
Promotion materializes only that node and resumes the native state machine.

## Format 1

Integers are big endian. All offsets and section lengths are 64 bit. The fixed
256-byte manifest contains:

| Offset | Field |
| --- | --- |
| 0 | 8-byte `BZSGDEPS` magic |
| 8 / 12 | Format version / manifest byte length, 32 bits each |
| 16 / 24 | Generation UUID, two 64-bit words |
| 32 | Snapshot-coupled flag, required to be 1 |
| 36 / 40 | Node count / canonical key count, 32 bits each |
| 44 | Reserved zero |
| 48 / 56 / 64 | Node-record / direct-vector / reverse-vector section lengths |
| 72 | Exact 40-byte Bazel source revision |
| 112 | SHA-256 of the entire file with this 32-byte field zeroed |
| 144–255 | Reserved zero bytes |

Node records immediately follow. Each is 48 bytes: key ID, retained wrapped-value
handle, last-changed-version handle, last-evaluated-version handle (four 32-bit
fields), then direct offset/length and reverse offset/length (four 64-bit fields).
Value and version handles identify the corresponding completed Java record in the
matching JVM generation. No addresses are stored, and no API reconstructs Java
metadata from this file alone.

Each direct block contains group count, dependency count, a vector of group
sizes, and a flattened vector of key IDs. Both group order and within-group order
are preserved. Each reverse block contains a count and key IDs. Sections are
packed; small records are not page padded.

Before activation the reader checks the checksum, generation, revision, format,
flag, reserved fields, Java-table dimensions, exact section coverage, ranges,
offset arithmetic, counts, group boundaries, key IDs, metadata handles, duplicate
edges and file length. Malformed input fails before any handle is transitioned.
The initial deployment uses Linux filesystems with hard-link support: atomic
hard-link publication refuses to replace an existing generation. Files are
published read-only and must never be rewritten or truncated while live.

## Reads, materialization and retirement

The JDK 25 `FileChannel.map` / foreign memory API maps the file read-only and uses
long offsets, avoiding a single `ByteBuffer`'s integer address limit. Flat and
group iterators resolve IDs on demand without rebuilding or caching arrays.
Reverse vectors expose read-only list views. The legacy compressed-array adapter
and promotion are explicit materialization boundaries.

`mappedBytes`, `materializedDependencyCount`, `copiedDependencyCount`,
`copiedMappedBytes` and `promotionsByReason` distinguish arena size, materialized
key IDs, promoted key IDs, encoded arena bytes copied on promotion, and reasons.
They are aggregate private Java counters. They do not measure retained heap,
resident memory, or physical host sharing. Canonical keys, node handles, retained
values, caller-retained materializations and JVM overhead remain additional costs.

Automatic arena lifetime keeps mappings alive while any node or caller-retained
reader references the store. Reset/cleanup drops graph ownership without
unmapping beneath live readers. Actual unmapping follows garbage collection once
all readers are gone. Generation files are retained; the VM controller must
retire them only after every clone using that generation has ended.

## Validation and remaining gates

The same inherited evaluator suite freezes either heap or mapped storage after
its first successful evaluation. Both backends run stable-handle, live-view,
interruption, delete/recreate, version/group preservation and concurrency tests.
The seeded harness compares native, frozen heap and mapped backends over 100
invalidation rounds plus periodic deletions against independent expected values.

Specific mapped tests verify that ordinary traversal does not materialize arrays,
promotion leaves file bytes unchanged, old arrays and delegates are collectable,
readers survive graph retirement, construction failure leaves the graph usable,
and corrupted/truncated arenas or mismatched generations/revisions/tables are
rejected. Structural corruption cases recompute the checksum so bounds and shape
validation are exercised independently of integrity checking.

These tests do not establish physical sharing or fleet memory savings. There is
still no VM preparation/resume command or restore barrier in this milestone.
The plan's warmed-snapshot control, real-workload retained-heap inspection,
conversion peak, pressure/refault tests and memory/CPU/latency gates remain
required before deployment or selection of another payload family.
