// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import static com.google.common.base.Preconditions.checkState;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.vfs.FileStateKey;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import com.google.devtools.build.skyframe.InMemoryGraph;
import com.google.devtools.build.skyframe.LayeredInMemoryGraph;
import com.google.devtools.build.skyframe.SkyKey;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Command-excluded lifecycle for the initial trusted, offline microVM experiment. */
final class SharedGraphLifecycle {
  private enum State {
    READY,
    PREPARED,
    RECONCILING
  }

  private enum ObservationKind {
    FILE_STATE,
    DIRECTORY_LISTING,
    OUTPUT,
    TREE_OUTPUT
  }

  private record Observation(SkyKey key, Path path, ObservationKind kind, byte[] fingerprint) {}

  private record Location(Path path, ObservationKind kind) {}

  private final String mode;
  private final ReadonlyPathVerifier readonlyPathVerifier;
  private ImmutableFiles immutableFiles;
  private State state = State.READY;
  private boolean eligible;
  private boolean preparationComplete;
  private boolean allowVerifiedCleanClone;
  private String reconciliationEvidence = "none";
  private boolean arenaRebound;
  private boolean fallback;
  private UUID generation;
  private String file;
  private String failure;
  private long mappedBytes;
  private long reconciliationNanos;
  private int changedNodes;
  private ImmutableList<Map<String, String>> changedObservations = ImmutableList.of();
  private ImmutableList<Observation> observations = ImmutableList.of();
  private final Map<String, Map<String, Long>> phaseMetrics = new LinkedHashMap<>();
  private final Map<String, Map<String, Long>> fingerprintIo = new LinkedHashMap<>();
  private Map<String, Object> retentionAudit = Map.of();

  @VisibleForTesting
  interface ReadonlyPathVerifier {
    java.nio.file.Path verify(String path) throws IOException;

    default void refresh() throws IOException {}
  }

  private record ImmutableRoot(java.nio.file.Path declared, java.nio.file.Path canonical) {}

  private record LinuxMount(java.nio.file.Path path, boolean readOnly) {}

  /** FileStore's device-based mount lookup misses read-only bind views on a writable device. */
  private static final class NativeReadonlyPathVerifier implements ReadonlyPathVerifier {
    private ImmutableList<LinuxMount> mounts;

    @Override
    public void refresh() throws IOException {
      var mountinfo = java.nio.file.Path.of("/proc/self/mountinfo");
      mounts =
          java.nio.file.Files.exists(mountinfo)
              ? parseLinuxMounts(java.nio.file.Files.readString(mountinfo))
              : null;
    }

    @Override
    public java.nio.file.Path verify(String name) throws IOException {
      var canonical = java.nio.file.Path.of(name).toRealPath();
      boolean readOnly =
          mounts == null
              ? java.nio.file.Files.getFileStore(canonical).isReadOnly()
              : readonlyLinuxMount(canonical, mounts);
      if (!readOnly) {
        throw new IOException("Immutable input requires a read-only filesystem: " + name);
      }
      return canonical;
    }
  }

  private static ImmutableList<LinuxMount> parseLinuxMounts(String text) throws IOException {
    var mounts = ImmutableList.<LinuxMount>builder();
    for (String line : text.lines().toList()) {
      var fields = line.split(" +");
      int separator = Arrays.asList(fields).indexOf("-");
      if (fields.length < 10 || separator < 6 || separator + 3 >= fields.length) {
        throw new IOException("Malformed Linux mountinfo; immutable proof unavailable");
      }
      String decoded =
          fields[4]
              .replace("\\040", " ")
              .replace("\\011", "\t")
              .replace("\\012", "\n")
              .replace("\\134", "\\");
      var path = java.nio.file.Path.of(decoded);
      var options = Arrays.asList(fields[5].split(","));
      if (!path.isAbsolute()
          || !path.normalize().equals(path)
          || !(options.contains("ro") || options.contains("rw"))) {
        throw new IOException("Invalid Linux mount identity/options");
      }
      mounts.add(new LinuxMount(path, options.contains("ro") && !options.contains("rw")));
    }
    return mounts.build();
  }

  private static boolean readonlyLinuxMount(
      java.nio.file.Path canonical, ImmutableList<LinuxMount> mounts) {
    int longest = -1;
    int matches = 0;
    boolean readOnly = false;
    for (var mount : mounts) {
      if (canonical.startsWith(mount.path())) {
        int depth = mount.path().getNameCount();
        if (depth > longest) {
          longest = depth;
          matches = 1;
          readOnly = mount.readOnly();
        } else if (depth == longest) {
          matches++;
        }
      }
    }
    return longest >= 0 && matches == 1 && readOnly;
  }

  @VisibleForTesting
  static boolean readonlyLinuxMount(java.nio.file.Path canonical, String mountinfo)
      throws IOException {
    return readonlyLinuxMount(canonical, parseLinuxMounts(mountinfo));
  }

  /** The controller also guarantees that the immutable backing generation cannot be replaced. */
  private record ImmutableFiles(ImmutableList<ImmutableRoot> roots, ReadonlyPathVerifier verifier) {
    static ImmutableFiles create(List<String> paths, ReadonlyPathVerifier verifier)
        throws IOException {
      if (!paths.isEmpty()) {
        verifier.refresh();
      }
      var roots = ImmutableList.<ImmutableRoot>builder();
      for (String name : paths) {
        var declared = java.nio.file.Path.of(name);
        if (!declared.isAbsolute()
            || declared.getParent() == null
            || !declared.normalize().equals(declared)) {
          throw new IllegalArgumentException(
              "Immutable roots must be normalized absolute non-root paths");
        }
        roots.add(new ImmutableRoot(declared, verifier.verify(name)));
      }
      return new ImmutableFiles(roots.build(), verifier);
    }

    void verifyRoots() throws IOException {
      if (!roots.isEmpty()) {
        // One mount snapshot per excluded pass, rather than retained per-file mount-table copies.
        // The controller excludes mount/image changes while a verification pass is in progress.
        verifier.refresh();
      }
      for (var root : roots) {
        if (!verifier.verify(root.declared().toString()).equals(root.canonical())) {
          throw new IOException("Immutable root changed canonical identity: " + root.declared());
        }
      }
    }

    boolean contains(Path path) throws IOException {
      var candidate = java.nio.file.Path.of(path.getPathString());
      if (roots.stream().noneMatch(root -> candidate.startsWith(root.declared()))) {
        return false;
      }
      // A nested writable mount or an escaping symlink requires ordinary content checks. Aliases
      // between two declared immutable roots are valid (external repositories commonly use these).
      java.nio.file.Path canonical;
      try {
        canonical = verifier.verify(candidate.toString());
      } catch (IOException unavailableProof) {
        return false;
      }
      return roots.stream().anyMatch(root -> canonical.startsWith(root.canonical()));
    }
  }

  /** One scratch buffer per excluded verification pass, including recursive output trees. */
  private static final class FingerprintContext {
    private final ImmutableFiles immutableFiles;
    private byte[] chunk;
    private long regularFiles;
    private long contentBytes;
    private long immutableFilesSkipped;

    FingerprintContext() {
      this(new ImmutableFiles(ImmutableList.of(), new NativeReadonlyPathVerifier()));
    }

    FingerprintContext(ImmutableFiles immutableFiles) {
      this.immutableFiles = immutableFiles;
    }

    byte[] buffer() {
      if (chunk == null) {
        chunk = new byte[64 * 1024];
      }
      return chunk;
    }

    Map<String, Long> metrics() {
      return Map.of(
          "regular_files", regularFiles,
          "content_bytes_read", contentBytes,
          "immutable_files_content_skipped", immutableFilesSkipped,
          "buffer_bytes_allocated", chunk == null ? 0L : (long) chunk.length);
    }
  }

  /** Aggregate command-thread diagnostics; never traverses or writes frozen node records. */
  final class Phase implements AutoCloseable {
    private final String name;
    private final long started = System.nanoTime();
    private final long allocated = allocatedBytes();
    private final long heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    private final long collections = gcCount();
    private final long collectionMillis = gcMillis();

    Phase(String name) {
      this.name = name;
    }

    @Override
    public void close() {
      long afterAllocated = allocatedBytes();
      phaseMetrics.put(
          name,
          Map.of(
              "wall_ns",
              System.nanoTime() - started,
              "command_thread_allocated_bytes",
              allocated < 0 || afterAllocated < 0 ? -1L : afterAllocated - allocated,
              "heap_used_before_bytes",
              heap,
              "heap_used_after_bytes",
              ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
              "gc_collections",
              gcCount() - collections,
              "gc_millis",
              gcMillis() - collectionMillis));
    }
  }

  Phase phase(String name) {
    return new Phase(name);
  }

  private static long allocatedBytes() {
    var bean = ManagementFactory.getThreadMXBean();
    if (bean instanceof com.sun.management.ThreadMXBean allocation
        && allocation.isThreadAllocatedMemorySupported()
        && allocation.isThreadAllocatedMemoryEnabled()) {
      return allocation.getCurrentThreadAllocatedBytes();
    }
    return -1;
  }

  private static long gcCount() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(bean -> Math.max(0, bean.getCollectionCount()))
        .sum();
  }

  private static long gcMillis() {
    return ManagementFactory.getGarbageCollectorMXBeans().stream()
        .mapToLong(bean -> Math.max(0, bean.getCollectionTime()))
        .sum();
  }

  SharedGraphLifecycle(String mode) {
    this(mode, new NativeReadonlyPathVerifier());
  }

  @VisibleForTesting
  SharedGraphLifecycle(String mode, ReadonlyPathVerifier readonlyPathVerifier) {
    checkState(
        ImmutableSet.of("off", "heap", "mapped").contains(mode), "Invalid shared graph mode");
    this.mode = mode;
    this.readonlyPathVerifier = readonlyPathVerifier;
    this.immutableFiles = new ImmutableFiles(ImmutableList.of(), readonlyPathVerifier);
  }

  boolean enabled() {
    return !mode.equals("off") && !fallback;
  }

  String commandRejection(String command) {
    if (state != State.READY && !command.equals("shared_graph") && !command.equals("shutdown")) {
      if (state == State.PREPARED && !preparationComplete) {
        return "Shared graph is PREPARED; preparation did not finish, discard this generation"
            + " first";
      }
      return "Shared graph is " + state + "; reconcile the matching generation or discard it first";
    }
    if (!command.equals("shared_graph")) {
      eligible = false;
    }
    return null;
  }

  void noteCommandResult(String command, boolean successfulAnalysisOnly) {
    if (!command.equals("shared_graph")) {
      eligible = enabled() && command.equals("build") && successfulAnalysisOnly;
    }
  }

  void prepare(InMemoryGraph graph, String directory, String revision)
      throws IOException, InterruptedException {
    prepare(graph, directory, revision, false);
  }

  void prepare(InMemoryGraph graph, String directory, String revision, boolean verifyRetention)
      throws IOException, InterruptedException {
    prepare(graph, directory, revision, verifyRetention, ImmutableList.of(), () -> {});
  }

  void prepare(
      InMemoryGraph graph,
      String directory,
      String revision,
      boolean verifyRetention,
      List<String> immutableRoots)
      throws IOException, InterruptedException {
    prepare(graph, directory, revision, verifyRetention, immutableRoots, () -> {});
  }

  void prepare(
      InMemoryGraph graph,
      String directory,
      String revision,
      boolean verifyRetention,
      List<String> immutableRoots,
      boolean allowVerifiedCleanClone)
      throws IOException, InterruptedException {
    prepare(
        graph,
        directory,
        revision,
        verifyRetention,
        immutableRoots,
        () -> {},
        allowVerifiedCleanClone);
  }

  @VisibleForTesting
  void prepare(
      InMemoryGraph graph,
      String directory,
      String revision,
      boolean verifyRetention,
      Runnable afterFreeze)
      throws IOException, InterruptedException {
    prepare(graph, directory, revision, verifyRetention, ImmutableList.of(), afterFreeze);
  }

  private void prepare(
      InMemoryGraph graph,
      String directory,
      String revision,
      boolean verifyRetention,
      List<String> immutableRoots,
      Runnable afterFreeze)
      throws IOException, InterruptedException {
    prepare(graph, directory, revision, verifyRetention, immutableRoots, afterFreeze, false);
  }

  private void prepare(
      InMemoryGraph graph,
      String directory,
      String revision,
      boolean verifyRetention,
      List<String> immutableRoots,
      Runnable afterFreeze,
      boolean allowVerifiedCleanClone)
      throws IOException, InterruptedException {
    checkState(
        enabled() && graph instanceof LayeredInMemoryGraph, "Shared graph backend is disabled");
    checkState(
        state == State.READY && generation == null && eligible,
        "Preparation requires the immediately preceding successful build --nobuild");
    // Consume the successful-build admission token. A failed attempt may have observed a changing
    // filesystem, so require ordinary analysis again before a new preparation attempt.
    eligible = false;
    immutableFiles = ImmutableFiles.create(immutableRoots, readonlyPathVerifier);
    this.allowVerifiedCleanClone = allowVerifiedCleanClone;
    reconciliationEvidence = "none";
    arenaRebound = false;
    // The controller must hold filesystem/workload exclusion continuously from that build through
    // snapshot publication. Re-read fingerprints to reject a filesystem changing during capture.
    phaseMetrics.clear();
    fingerprintIo.clear();
    preparationComplete = false;
    ImmutableList<Observation> staged;
    ImmutableList<Observation> second;
    try (var phase = phase("prepare_capture_first")) {
      staged = capture(graph, "prepare_capture_first");
    }
    try (var phase = phase("prepare_capture_verify")) {
      second = capture(graph, "prepare_capture_verify");
    }
    checkState(staged.size() == second.size(), "Graph changed during preparation");
    for (int i = 0; i < staged.size(); i++) {
      checkState(
          staged.get(i).key().equals(second.get(i).key())
              && staged.get(i).path().equals(second.get(i).path())
              && Arrays.equals(staged.get(i).fingerprint(), second.get(i).fingerprint()),
          "Filesystem changed during preparation");
    }
    var layered = (LayeredInMemoryGraph) graph;
    // Reserve admission before publication, including failures in phase-metric cleanup. A failed
    // construction can reopen admission only after confirming that no baseline was committed.
    state = State.PREPARED;
    try {
      UUID id;
      if (mode.equals("mapped")) {
        checkState(
            !directory.isEmpty() && java.nio.file.Path.of(directory).isAbsolute(),
            "Mapped preparation requires an absolute arena directory");
        try (var phase = phase("prepare_freeze")) {
          id =
              layered.freezeMappedAtQuiescentBoundary(
                  java.nio.file.Path.of(directory), revision, verifyRetention);
          afterFreeze.run();
        }
      } else {
        try (var phase = phase("prepare_freeze")) {
          id = layered.freezeAtQuiescentBoundary(verifyRetention);
          afterFreeze.run();
        }
      }
      observations = staged;
      generation = id;
      mappedBytes = layered.mappedBytes();
      file = layered.backingFile() == null ? null : layered.backingFile().toString();
      // A committed baseline remains blocked if cleanup or prefaulting fails.
      eligible = false;
      try (var phase = phase("prepare_gc")) {
        System.gc();
      }
      if (verifyRetention) {
        try (var phase = phase("prepare_retention_audit")) {
          retentionAudit = layered.finishRetentionAudit();
        }
      }
      try (var phase = phase("prepare_prefault")) {
        layered.prefaultBaseline();
      }
      preparationComplete = true;
    } catch (IOException | InterruptedException | RuntimeException | Error failure) {
      if (layered.baselineGeneration() == null) {
        state = State.READY;
      } else {
        // Publication is already committed. Failure reporting/metrics can itself run out of
        // memory, so close admission with allocation-free stores before propagating the error.
        state = State.PREPARED;
        eligible = false;
        preparationComplete = false;
        generation = layered.baselineGeneration();
        mappedBytes = layered.mappedBytes();
        observations = staged;
        this.failure = "Preparation failed after graph freeze; discard is required";
      }
      throw failure;
    }
  }

  private ImmutableList<Observation> capture(InMemoryGraph graph, String phase)
      throws IOException, InterruptedException {
    var context = new FingerprintContext(immutableFiles);
    try {
      immutableFiles.verifyRoots();
      return capture(graph, context);
    } finally {
      fingerprintIo.put(phase, context.metrics());
    }
  }

  private static ImmutableList<Observation> capture(InMemoryGraph graph, FingerprintContext context)
      throws IOException, InterruptedException {
    var records = ImmutableList.<Observation>builder();
    var cache = new HashMap<Location, byte[]>();
    // Stable ordering also provides a repeatability check across the two captures.
    var entries =
        graph.getAllNodeEntries().stream()
            .sorted(Comparator.comparing(e -> e.getKey().getCanonicalName()))
            .toList();
    for (var entry : entries) {
      checkInterrupted();
      SkyKey key = entry.getKey();
      if (key instanceof FileStateKey file) {
        observe(records, cache, key, file.argument().asPath(), ObservationKind.FILE_STATE, context);
      } else if (key.functionName().equals(SkyFunctions.DIRECTORY_LISTING_STATE)
          && key.argument() instanceof RootedPath path) {
        observe(records, cache, key, path.asPath(), ObservationKind.DIRECTORY_LISTING, context);
      }
      if (entry.isDone() && entry.getValue() instanceof ActionExecutionValue action) {
        for (var artifact : action.getAllFileValues().keySet()) {
          observe(records, cache, key, artifact.getPath(), ObservationKind.OUTPUT, context);
        }
        for (var artifact : action.getAllTreeArtifactValues().keySet()) {
          observe(records, cache, key, artifact.getPath(), ObservationKind.TREE_OUTPUT, context);
        }
      }
    }
    return records.build();
  }

  private static void observe(
      ImmutableList.Builder<Observation> records,
      Map<Location, byte[]> cache,
      SkyKey key,
      Path path,
      ObservationKind kind,
      FingerprintContext context)
      throws IOException, InterruptedException {
    var location = new Location(path, kind);
    byte[] value = cache.get(location);
    if (value == null) {
      value = fingerprint(path, kind, context);
      cache.put(location, value);
    }
    records.add(new Observation(key, path, kind, value));
  }

  static byte[] fingerprint(Path path, boolean recursive) throws IOException, InterruptedException {
    return fingerprint(
        path,
        recursive ? ObservationKind.TREE_OUTPUT : ObservationKind.OUTPUT,
        new FingerprintContext());
  }

  private static byte[] fingerprint(Path path, ObservationKind kind, FingerprintContext context)
      throws IOException, InterruptedException {
    checkInterrupted();
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
    var stat = path.statIfFound(Symlinks.NOFOLLOW);
    if (stat == null) {
      return digest.digest(new byte[] {0});
    }
    // Native directory FileStateValue equality is type-only. Listing membership belongs to a
    // separate key; neither observation should change merely because directory metadata changed.
    if (stat.isDirectory() && kind == ObservationKind.FILE_STATE) {
      return digest.digest(new byte[] {2});
    }
    if (kind != ObservationKind.DIRECTORY_LISTING) {
      digest.update(
          (stat.getNodeId()
                  + ":"
                  + stat.getLastModifiedTimeNanos()
                  + ":"
                  + stat.getLastChangeTimeNanos()
                  + ":"
                  + stat.getPermissions()
                  + ":")
              .getBytes(StandardCharsets.UTF_8));
    }
    if (stat.isSymbolicLink()) {
      digest.update((byte) 1);
      digest.update(path.readSymbolicLink().getPathString().getBytes(StandardCharsets.UTF_8));
    } else if (stat.isDirectory()) {
      digest.update((byte) 2);
      var entries =
          path.readdir(Symlinks.NOFOLLOW).stream()
              .sorted(Comparator.comparing(com.google.devtools.build.lib.vfs.Dirent::getName))
              .toList();
      for (var entry : entries) {
        checkInterrupted();
        digest.update(entry.getName().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(entry.getType().toString().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        if (kind == ObservationKind.TREE_OUTPUT) {
          digest.update(
              fingerprint(path.getChild(entry.getName()), ObservationKind.TREE_OUTPUT, context));
        }
      }
    } else if (stat.isFile() && !stat.isSpecialFile()) {
      digest.update((byte) 3);
      digest.update(Long.toString(stat.getSize()).getBytes(StandardCharsets.UTF_8));
      if (context.immutableFiles.contains(path)) {
        // The controller binds this read-only filesystem to the saved immutable image. Metadata
        // checks remain, but scanning toolchain contents would only populate private guest cache.
        digest.update((byte) 5);
        context.immutableFilesSkipped++;
        return digest.digest();
      }
      try (var input = path.getInputStream()) {
        context.regularFiles++;
        byte[] chunk = context.buffer();
        int count;
        while ((count = input.read(chunk)) != -1) {
          checkInterrupted();
          digest.update(chunk, 0, count);
          context.contentBytes += count;
        }
      }
    } else {
      digest.update((byte) 4); // Special-file state is metadata, never a blocking device read.
    }
    return digest.digest();
  }

  void rebindArena(InMemoryGraph graph, String target, String expectedGeneration)
      throws IOException, InterruptedException {
    rebindArena(graph, target, expectedGeneration, () -> {});
  }

  @VisibleForTesting
  void rebindArena(
      InMemoryGraph graph, String target, String expectedGeneration, Runnable afterSwap)
      throws IOException, InterruptedException {
    checkState(
        state == State.PREPARED && preparationComplete && generation != null,
        "Arena rebind requires a complete prepared generation");
    checkState(
        "mapped".equals(mode) && graph instanceof LayeredInMemoryGraph,
        "Arena rebind requires the mapped backend");
    checkState(generation.toString().equals(expectedGeneration), "Generation mismatch");
    checkState(!arenaRebound, "Arena backing was already rebound");
    var requested = java.nio.file.Path.of(target);
    if (!requested.isAbsolute() || !requested.normalize().equals(requested)) {
      throw new IllegalArgumentException("Rebind arena must be a normalized absolute file path");
    }
    readonlyPathVerifier.refresh();
    var canonical = readonlyPathVerifier.verify(target);
    if (!java.nio.file.Files.isRegularFile(canonical, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Rebind arena is not a regular file");
    }
    String canonicalName = canonical.toString();
    var layered = (LayeredInMemoryGraph) graph;
    // Any failure after this boundary requires discard. In particular, a
    // post-swap GC/prefault/metrics failure cannot reopen ordinary admission.
    preparationComplete = false;
    failure = "Arena rebind did not finish; discard this generation";
    try (var phase = phase("rebind_arena")) {
      layered.rebindMappedBaseline(canonical);
      file = canonicalName;
      arenaRebound = true;
      afterSwap.run();
    }
    try (var phase = phase("rebind_gc")) {
      System.gc();
    }
    try (var phase = phase("rebind_prefault")) {
      layered.prefaultBaseline();
    }
    checkInterrupted();
    preparationComplete = true;
    failure = null;
  }

  void beginReconciliation(String expectedGeneration) {
    checkState(
        state == State.PREPARED && generation != null,
        "No prepared generation awaits reconciliation");
    checkState(preparationComplete, "Preparation did not finish; discard this generation first");
    checkState(generation.toString().equals(expectedGeneration), "Generation mismatch");
    state = State.RECONCILING;
    reconciliationNanos = System.nanoTime();
  }

  void verifyReboundArenaFilesystem() throws IOException {
    if (!arenaRebound) {
      return;
    }
    readonlyPathVerifier.refresh();
    var canonical = readonlyPathVerifier.verify(file);
    if (!canonical.toString().equals(file)
        || !java.nio.file.Files.isRegularFile(canonical, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Rebound arena filesystem identity changed");
    }
  }

  ImmutableSet<SkyKey> changedKeys() throws IOException, InterruptedException {
    reconciliationEvidence = "full-mutable-content-and-immutable-root-proof";
    var context = new FingerprintContext(immutableFiles);
    try {
      verifyReboundArenaFilesystem();
      immutableFiles.verifyRoots();
      return changedKeys(context);
    } finally {
      fingerprintIo.put("reconcile_fingerprints", context.metrics());
    }
  }

  ImmutableSet<SkyKey> unchangedKeysFromVerifiedClone() throws IOException, InterruptedException {
    checkState(state == State.RECONCILING, "No reconciliation is in progress");
    checkState(
        allowVerifiedCleanClone,
        "This generation does not permit controller-verified clean clones");
    // This is an explicit trusted-controller contract, not filesystem evidence
    // inferred by this JVM. The controller must prove an exact, unmodified
    // whole-filesystem clone paired with this prepared JVM, exclude input and
    // output writers until reconciliation, and withhold the assertion otherwise.
    // Without that assertion the ordinary full-content path remains mandatory.
    verifyReboundArenaFilesystem();
    immutableFiles.verifyRoots();
    checkInterrupted();
    reconciliationEvidence = "controller-verified-unchanged-filesystem-clone";
    fingerprintIo.put(
        "reconcile_fingerprints",
        Map.of(
            "regular_files", 0L,
            "content_bytes_read", 0L,
            "buffer_bytes_allocated", 0L,
            "immutable_files_content_skipped", 0L,
            "observations_verified_by_clone_continuity", (long) observations.size()));
    changedObservations = ImmutableList.of();
    return ImmutableSet.of();
  }

  private ImmutableSet<SkyKey> changedKeys(FingerprintContext context)
      throws IOException, InterruptedException {
    checkState(state == State.RECONCILING);
    var changed = ImmutableSet.<SkyKey>builder();
    var details = ImmutableList.<Map<String, String>>builder();
    int detailCount = 0;
    var cache = new HashMap<Location, byte[]>();
    for (var observation : observations) {
      checkInterrupted();
      var location = new Location(observation.path(), observation.kind());
      byte[] actual = cache.get(location);
      if (actual == null) {
        actual = fingerprint(location.path(), location.kind(), context);
        cache.put(location, actual);
      }
      if (!Arrays.equals(actual, observation.fingerprint())) {
        changed.add(observation.key());
        if (detailCount++ < 256) {
          details.add(
              Map.of(
                  "key", observation.key().getCanonicalName(),
                  "path", observation.path().getPathString(),
                  "kind", observation.kind().name(),
                  "before", java.util.HexFormat.of().formatHex(observation.fingerprint()),
                  "after", java.util.HexFormat.of().formatHex(actual)));
        }
      }
    }
    changedObservations = details.build();
    return changed.build();
  }

  void finishReconciliation(int changedNodes) {
    checkState(state == State.RECONCILING);
    this.changedNodes = changedNodes;
    reconciliationNanos = System.nanoTime() - reconciliationNanos;
    state = State.READY;
    observations = ImmutableList.of();
    failure = null;
  }

  void failReconciliation(String message) {
    reconciliationNanos = System.nanoTime() - reconciliationNanos;
    state = State.PREPARED;
    failure = message;
  }

  void retire() {
    state = State.READY;
    generation = null;
    preparationComplete = false;
    allowVerifiedCleanClone = false;
    reconciliationEvidence = "none";
    arenaRebound = false;
    file = null;
    observations = ImmutableList.of();
    mappedBytes = 0;
    eligible = false;
    immutableFiles = new ImmutableFiles(ImmutableList.of(), readonlyPathVerifier);
  }

  void fallback() {
    retire();
    fallback = true;
    failure = "Explicit full evaluator fallback";
  }

  Map<String, Object> status() {
    var status = new LinkedHashMap<String, Object>();
    status.put("schema_version", 1);
    status.put("mode", fallback ? "off" : mode);
    status.put("state", state.name());
    status.put("preparation_complete", preparationComplete);
    status.put("allow_controller_verified_clean_clone", allowVerifiedCleanClone);
    status.put("reconciliation_change_evidence", reconciliationEvidence);
    status.put("arena_rebound", arenaRebound);
    status.put("generation", generation == null ? null : generation.toString());
    status.put("arena", file);
    status.put("mapped_bytes", mappedBytes);
    var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    status.put("heap_used_bytes", heap.getUsed());
    status.put("heap_committed_bytes", heap.getCommitted());
    status.put("heap_max_bytes", heap.getMax());
    status.put("private_graph_bytes", null);
    status.put("cache_bytes", null);
    status.put(
        "memory_metrics_scope",
        "Heap bytes are whole-JVM usage; mapped bytes are arena extent, not residency."
            + " Private graph and cache bytes are not attributed. The controller separately"
            + " measures whole-VMM private resident bytes for its soft command-boundary budget.");
    status.put("fingerprints", observations.size());
    status.put(
        "immutable_roots",
        immutableFiles.roots().stream()
            .map(
                root ->
                    Map.of(
                        "declared",
                        root.declared().toString(),
                        "canonical",
                        root.canonical().toString()))
            .toList());
    status.put(
        "immutable_roots_scope",
        "Explicit controller-attested immutable backing on read-only filesystems; roots and files"
            + " are rechecked. Mutable inputs retain full content fingerprints. This is not"
            + " evidence of resident memory or physical sharing.");
    status.put("changed_nodes", changedNodes);
    status.put("changed_observations", changedObservations);
    status.put("reconciliation_ns", reconciliationNanos);
    status.put("fallback", fallback);
    status.put("failure", failure);
    status.put("phase_metrics", new LinkedHashMap<>(phaseMetrics));
    status.put("fingerprint_io", new LinkedHashMap<>(fingerprintIo));
    status.put("retention_audit", retentionAudit);
    status.put(
        "retention_audit_scope",
        "Opt-in post-GC weak-reference counts for successfully frozen nodes."
            + " Excludes singleton keys and empty global storage. Heap baselines intentionally"
            + " retain dependency storage. Diagnostic references are discarded after sampling;"
            + " these runs cannot establish performance or physical sharing.");
    status.put(
        "phase_metrics_scope",
        "Wall time and command-thread allocation (-1 if unavailable); heap used and GC are"
            + " JVM-wide. These are diagnostics, not retained bytes or private physical pages.");
    return status;
  }

  private static void checkInterrupted() throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
  }
}
