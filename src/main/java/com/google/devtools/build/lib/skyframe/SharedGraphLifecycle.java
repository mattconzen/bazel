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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
  private State state = State.READY;
  private boolean eligible;
  private boolean fallback;
  private UUID generation;
  private String file;
  private String failure;
  private long mappedBytes;
  private long reconciliationNanos;
  private int changedNodes;
  private ImmutableList<Map<String, String>> changedObservations = ImmutableList.of();
  private ImmutableList<Observation> observations = ImmutableList.of();

  SharedGraphLifecycle(String mode) {
    checkState(
        ImmutableSet.of("off", "heap", "mapped").contains(mode), "Invalid shared graph mode");
    this.mode = mode;
  }

  boolean enabled() {
    return !mode.equals("off") && !fallback;
  }

  String commandRejection(String command) {
    if (state != State.READY && !command.equals("shared_graph") && !command.equals("shutdown")) {
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
    checkState(
        enabled() && graph instanceof LayeredInMemoryGraph, "Shared graph backend is disabled");
    checkState(
        state == State.READY && generation == null && eligible,
        "Preparation requires the immediately preceding successful build --nobuild");
    // The controller must hold filesystem/workload exclusion continuously from that build through
    // snapshot publication. Re-read fingerprints to reject a filesystem changing during capture.
    var staged = capture(graph);
    var second = capture(graph);
    checkState(staged.size() == second.size(), "Graph changed during preparation");
    for (int i = 0; i < staged.size(); i++) {
      checkState(
          staged.get(i).key().equals(second.get(i).key())
              && staged.get(i).path().equals(second.get(i).path())
              && Arrays.equals(staged.get(i).fingerprint(), second.get(i).fingerprint()),
          "Filesystem changed during preparation");
    }
    var layered = (LayeredInMemoryGraph) graph;
    UUID id;
    if (mode.equals("mapped")) {
      checkState(
          !directory.isEmpty() && java.nio.file.Path.of(directory).isAbsolute(),
          "Mapped preparation requires an absolute arena directory");
      id = layered.freezeMappedAtQuiescentBoundary(java.nio.file.Path.of(directory), revision);
    } else {
      id = layered.freezeAtQuiescentBoundary();
    }
    observations = staged;
    generation = id;
    mappedBytes = layered.mappedBytes();
    file = layered.backingFile() == null ? null : layered.backingFile().toString();
    // Close command admission before cleanup/prefaulting. Any failure after freeze remains blocked.
    state = State.PREPARED;
    eligible = false;
    System.gc();
    layered.prefaultBaseline();
  }

  private static ImmutableList<Observation> capture(InMemoryGraph graph)
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
        observe(records, cache, key, file.argument().asPath(), ObservationKind.FILE_STATE);
      } else if (key.functionName().equals(SkyFunctions.DIRECTORY_LISTING_STATE)
          && key.argument() instanceof RootedPath path) {
        observe(records, cache, key, path.asPath(), ObservationKind.DIRECTORY_LISTING);
      }
      if (entry.isDone() && entry.getValue() instanceof ActionExecutionValue action) {
        for (var artifact : action.getAllFileValues().keySet()) {
          observe(records, cache, key, artifact.getPath(), ObservationKind.OUTPUT);
        }
        for (var artifact : action.getAllTreeArtifactValues().keySet()) {
          observe(records, cache, key, artifact.getPath(), ObservationKind.TREE_OUTPUT);
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
      ObservationKind kind)
      throws IOException, InterruptedException {
    var location = new Location(path, kind);
    byte[] value = cache.get(location);
    if (value == null) {
      value = fingerprint(path, kind);
      cache.put(location, value);
    }
    records.add(new Observation(key, path, kind, value));
  }

  static byte[] fingerprint(Path path, boolean recursive) throws IOException, InterruptedException {
    return fingerprint(path, recursive ? ObservationKind.TREE_OUTPUT : ObservationKind.OUTPUT);
  }

  private static byte[] fingerprint(Path path, ObservationKind kind)
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
          digest.update(fingerprint(path.getChild(entry.getName()), true));
        }
      }
    } else if (stat.isFile() && !stat.isSpecialFile()) {
      digest.update((byte) 3);
      digest.update(Long.toString(stat.getSize()).getBytes(StandardCharsets.UTF_8));
      try (var input = path.getInputStream()) {
        byte[] chunk = new byte[64 * 1024];
        int count;
        while ((count = input.read(chunk)) != -1) {
          checkInterrupted();
          digest.update(chunk, 0, count);
        }
      }
    } else {
      digest.update((byte) 4); // Special-file state is metadata, never a blocking device read.
    }
    return digest.digest();
  }

  void beginReconciliation(String expectedGeneration) {
    checkState(
        state == State.PREPARED && generation != null,
        "No prepared generation awaits reconciliation");
    checkState(generation.toString().equals(expectedGeneration), "Generation mismatch");
    state = State.RECONCILING;
    reconciliationNanos = System.nanoTime();
  }

  ImmutableSet<SkyKey> changedKeys() throws IOException, InterruptedException {
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
        actual = fingerprint(location.path(), location.kind());
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
    file = null;
    observations = ImmutableList.of();
    mappedBytes = 0;
    eligible = false;
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
    status.put("generation", generation == null ? null : generation.toString());
    status.put("arena", file);
    status.put("mapped_bytes", mappedBytes);
    status.put("fingerprints", observations.size());
    status.put("changed_nodes", changedNodes);
    status.put("changed_observations", changedObservations);
    status.put("reconciliation_ns", reconciliationNanos);
    status.put("fallback", fallback);
    status.put("failure", failure);
    return status;
  }

  private static void checkInterrupted() throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
  }
}
