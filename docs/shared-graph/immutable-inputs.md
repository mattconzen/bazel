# Immutable inputs during preparation and restore

Repeated full-content verification populates private guest file cache. An
explicit immutable-input policy avoids those reads for controller-attested
read-only toolchain and repository inputs. Ordinary mutable inputs retain the
existing full-content fingerprints, including changes that preserve all file
metadata. This is an experimental memory change, with no speed target.

## Contract

The controller must bind each declared root to the same immutable image content
through preparation, snapshot publication and every clone's lifetime. It must
exclude privileged remounts, backing replacement and out-of-band writes. A
read-only mount flag alone does not establish that image identity.

The Java lifecycle requires normalized absolute non-root paths and verifies each
root's canonical path and read-only filesystem during both preparation captures
and reconciliation. A lost root proof fails preparation or leaves restoration
blocked. Ordinary command admission is unchanged.

For regular files under a declared root, canonical resolution and read-only
filesystem verification determine whether contents can be skipped. Aliases
between declared immutable roots are supported. Escaping symlinks, nested
writable mounts and unavailable per-file proof retain full-content checks.
Different fingerprint markers distinguish skipped contents from hashed contents,
so a loss of per-file trust invalidates the corresponding observations.

Existence, type, inode, size, timestamps and permissions remain observed. Symlink
targets, directory listings and recursive output handling keep their existing
semantics. No immutable-root declaration is enabled by default.

## Controller integration

After the final successful warm analysis, make the selected tool-input views
read-only before invoking preparation. Do this after initial repository setup,
which may need to update external-repository markers. Apply the same filesystem
views to native/off controls. Workspaces and outputs stay writable.

For example, the internal preparation command accepts repeated declarations:

```sh
bazel shared_graph prepare \
  --shared_graph_directory=/absolute/arena \
  --shared_graph_revision=EXACT_BINARY_REVISION \
  --shared_graph_immutable_root=/toolchains \
  --shared_graph_immutable_root=/absolute/repository-cache/contents \
  --shared_graph_immutable_root=/absolute/output-base/external
```

Only the trusted controller should supply declarations. Save image identity and
mount proofs with the generation; verify them before every restore. Required
repository refresh outside this bounded immutable-input configuration needs
explicit revalidation/fallback, not silent reuse of an old graph.

## Evidence and status

`status.immutable_roots` reports declared/canonical paths. The per-phase
`fingerprint_io.immutable_files_content_skipped` counter separates skipped
immutable files from regular files whose contents were read. Retirement/fallback
clears the saved root policy. These counters do not measure physical sharing or
resident memory.

Lifecycle tests cover unchanged immutable inputs alongside metadata-preserving
mutable edits, read-only proof rejection/loss, canonical escape and sibling
prefixes, aliases between roots and nested writable-file fallback. Graph,
executor and runtime suites qualify source behavior separately from live VM
memory measurements.

Live-guest mount verification, exact candidate binary/image qualification and
matched before/after memory comparison remain required by L-02 in
[LATEST.md](../../LATEST.md). Existing measurements of the earlier full-scanning
binary are baseline evidence; no saving is inferred for this implementation.
