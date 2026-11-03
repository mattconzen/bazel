// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.AbstractList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

/**
 * Read-only dependency arena coupled to canonical keys and completed metadata in this JVM snapshot.
 * Uses the supported JDK memory API with long offsets, including arenas larger than 2 GiB.
 * Automatic arena lifetime keeps mappings alive while any node or caller-retained reader uses them.
 */
final class MappedGraphStore extends FrozenGraphStore {
  static final int HEADER_BYTES = 256;
  static final int RECORD_BYTES = 48;
  static final int CHECKSUM_OFFSET = 112;
  private static final long MAGIC = 0x425a534744455053L; // BZSGDEPS
  private static final int FORMAT = 1;
  private static final ValueLayout.OfInt INT =
      ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
  private static final ValueLayout.OfLong LONG =
      ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

  private final MemorySegment data;
  private final SkyKey[] keys;
  private final Path file;
  private final String revision;
  private volatile long prefaultWitness;
  private final LongAdder materializedDependencies = new LongAdder();

  private MappedGraphStore(
      int size, UUID generation, Path file, String revision, SkyKey[] keys, MemorySegment data) {
    super(size, generation);
    this.file = file;
    this.revision = revision;
    this.keys = keys;
    this.data = data;
  }

  static MappedGraphStore create(
      Path directory, String revision, List<LayeredNodeEntry.FreezeCandidate> candidates)
      throws IOException, InterruptedException {
    if (!revision.matches("[0-9a-f]{40}")) {
      throw new IllegalArgumentException("An exact 40-character Bazel revision is required");
    }
    UUID generation = UUID.randomUUID();
    var keyIds = new LinkedHashMap<SkyKey, Integer>();
    for (var candidate : candidates) {
      keyIds.computeIfAbsent(candidate.handle().getKey(), unused -> keyIds.size());
    }
    long directBytes = 0;
    long reverseBytes = 0;
    for (var candidate : candidates) {
      checkInterrupted();
      var record = candidate.record();
      for (SkyKey key : record.directDeps()) {
        keyIds.computeIfAbsent(key, unused -> keyIds.size());
      }
      for (SkyKey key : record.reverseDeps()) {
        keyIds.computeIfAbsent(key, unused -> keyIds.size());
      }
      directBytes = Math.addExact(directBytes, directBytes(record.directDeps()));
      reverseBytes = Math.addExact(reverseBytes, reverseBytes(record.reverseDeps().size()));
    }
    SkyKey[] keys = keyIds.keySet().toArray(SkyKey[]::new);
    long nodeBytes = Math.multiplyExact((long) candidates.size(), RECORD_BYTES);
    ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
    header.putLong(0, MAGIC);
    header.putInt(8, FORMAT);
    header.putInt(12, HEADER_BYTES);
    header.putLong(16, generation.getMostSignificantBits());
    header.putLong(24, generation.getLeastSignificantBits());
    header.putInt(32, 1); // Snapshot-coupled; not a standalone import format.
    header.putInt(36, candidates.size());
    header.putInt(40, keys.length);
    header.putLong(48, nodeBytes);
    header.putLong(56, directBytes);
    header.putLong(64, reverseBytes);
    header.position(72);
    header.put(revision.getBytes(StandardCharsets.US_ASCII));
    directory = directory.toAbsolutePath();
    Files.createDirectories(directory);
    Path file = directory.resolve(generation + ".arena");
    Path temporary = Files.createTempFile(directory, ".shared-graph-", ".tmp");
    try {
      MessageDigest digest = digest();
      try (var out =
          new DataOutputStream(
              new BufferedOutputStream(
                  new DigestOutputStream(Files.newOutputStream(temporary), digest)))) {
        out.write(header.array());
        long directOffset = Math.addExact(HEADER_BYTES, nodeBytes);
        long reverseOffset = Math.addExact(directOffset, directBytes);
        for (int i = 0; i < candidates.size(); i++) {
          checkInterrupted();
          var candidate = candidates.get(i);
          var record = candidate.record();
          out.writeInt(keyIds.get(candidate.handle().getKey()));
          out.writeInt(i); // Wrapped-value handle retained by the corresponding JVM node.
          out.writeInt(i); // Last-changed version handle in that same completed record.
          out.writeInt(i); // Last-evaluated version handle in that same completed record.
          long directLength = directBytes(record.directDeps());
          long reverseLength = reverseBytes(record.reverseDeps().size());
          out.writeLong(directOffset);
          out.writeLong(directLength);
          out.writeLong(reverseOffset);
          out.writeLong(reverseLength);
          directOffset = Math.addExact(directOffset, directLength);
          reverseOffset = Math.addExact(reverseOffset, reverseLength);
        }
        for (var candidate : candidates) {
          checkInterrupted();
          DoneDeps deps = candidate.record().directDeps();
          out.writeInt(deps.groupCount());
          out.writeInt(deps.dependencyCount());
          for (var group : deps.groups()) {
            out.writeInt(group.size());
          }
          for (SkyKey dep : deps) {
            out.writeInt(keyIds.get(dep));
          }
        }
        for (var candidate : candidates) {
          checkInterrupted();
          var reverse = candidate.record().reverseDeps();
          out.writeInt(reverse.size());
          for (SkyKey dep : reverse) {
            out.writeInt(keyIds.get(dep));
          }
        }
      }
      byte[] checksum = digest.digest();
      try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
        ByteBuffer bytes = ByteBuffer.wrap(checksum);
        long offset = CHECKSUM_OFFSET;
        while (bytes.hasRemaining()) {
          offset += channel.write(bytes, offset);
        }
        channel.force(true);
      }
      MappedGraphStore store =
          openValidated(temporary, generation, revision, candidates.size(), keys);
      checkInterrupted();
      // Hard-link publication is atomic and refuses to overwrite any existing generation. This
      // initial Linux snapshot deployment deliberately requires a filesystem with hard-link
      // support.
      var publishedStore =
          new MappedGraphStore(store.size(), generation, file, revision, keys, store.data);
      Files.setPosixFilePermissions(temporary, Set.of(PosixFilePermission.OWNER_READ));
      Files.createLink(file, temporary);
      return publishedStore;
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  /** Requires the matching canonical key table and generation already present in this JVM. */
  static MappedGraphStore openValidated(
      Path file, UUID generation, String revision, int records, SkyKey[] keys) throws IOException {
    try {
      byte[] headerBytes;
      MessageDigest checksum = digest();
      byte[] expected;
      try (var in = new BufferedInputStream(Files.newInputStream(file))) {
        headerBytes = in.readNBytes(HEADER_BYTES);
        require(headerBytes.length == HEADER_BYTES, "Truncated manifest");
        expected = Arrays.copyOfRange(headerBytes, CHECKSUM_OFFSET, CHECKSUM_OFFSET + 32);
        Arrays.fill(headerBytes, CHECKSUM_OFFSET, CHECKSUM_OFFSET + 32, (byte) 0);
        checksum.update(headerBytes);
        byte[] chunk = new byte[64 * 1024];
        int count;
        while ((count = in.read(chunk)) != -1) {
          checksum.update(chunk, 0, count);
        }
      }
      require(MessageDigest.isEqual(expected, checksum.digest()), "Arena checksum mismatch");
      ByteBuffer header = ByteBuffer.wrap(headerBytes);
      require(header.getLong(0) == MAGIC && header.getInt(8) == FORMAT, "Unknown arena format");
      require(header.getInt(12) == HEADER_BYTES && header.getInt(32) == 1, "Not snapshot-coupled");
      require(
          header.getLong(16) == generation.getMostSignificantBits()
              && header.getLong(24) == generation.getLeastSignificantBits(),
          "Generation mismatch");
      require(
          revision.matches("[0-9a-f]{40}")
              && new String(headerBytes, 72, 40, StandardCharsets.US_ASCII).equals(revision),
          "Bazel revision mismatch");
      require(
          records >= 0 && header.getInt(36) == records && header.getInt(40) == keys.length,
          "Java handle table mismatch");
      require(header.getInt(44) == 0, "Invalid reserved field");
      for (int i = 144; i < HEADER_BYTES; i++) {
        require(headerBytes[i] == 0, "Invalid reserved manifest bytes");
      }
      long nodeBytes = header.getLong(48);
      long directBytes = header.getLong(56);
      long reverseBytes = header.getLong(64);
      require(
          nodeBytes == Math.multiplyExact((long) records, RECORD_BYTES)
              && directBytes >= 0
              && reverseBytes >= 0,
          "Invalid section lengths");
      long directStart = Math.addExact(HEADER_BYTES, nodeBytes);
      long reverseStart = Math.addExact(directStart, directBytes);
      long end = Math.addExact(reverseStart, reverseBytes);
      require(Files.size(file) == end, "Truncated or trailing arena data");
      MemorySegment mapping;
      try (var channel = FileChannel.open(file, StandardOpenOption.READ)) {
        mapping = channel.map(FileChannel.MapMode.READ_ONLY, 0, end, Arena.ofAuto());
      }
      var store = new MappedGraphStore(records, generation, file, revision, keys, mapping);
      store.validateRecords(directStart, reverseStart, end);
      return store;
    } catch (ArithmeticException | IndexOutOfBoundsException failure) {
      throw new IOException("Invalid arena bounds", failure);
    }
  }

  private void validateRecords(long directStart, long reverseStart, long end) throws IOException {
    long nextDirect = directStart;
    long nextReverse = reverseStart;
    for (int i = 0; i < size(); i++) {
      long node = HEADER_BYTES + (long) i * RECORD_BYTES;
      validateKey(getInt(node));
      require(
          getInt(node + 4) == i && getInt(node + 8) == i && getInt(node + 12) == i,
          "Invalid snapshot metadata handle");
      long directOffset = getLong(node + 16);
      long directLength = getLong(node + 24);
      long reverseOffset = getLong(node + 32);
      long reverseLength = getLong(node + 40);
      require(
          directOffset == nextDirect
              && directLength >= 8
              && directLength <= reverseStart - directOffset,
          "Invalid direct dependency range");
      require(
          reverseOffset == nextReverse
              && reverseLength >= 4
              && reverseLength <= end - reverseOffset,
          "Invalid reverse dependency range");
      int groups = getInt(directOffset);
      int deps = getInt(directOffset + 4);
      require(
          groups >= 0 && deps >= 0 && groups <= deps && (groups == 0) == (deps == 0),
          "Invalid dependency counts");
      require(
          directLength == 8 + Math.multiplyExact(4L, (long) groups + deps),
          "Invalid direct dependency length");
      long sum = 0;
      for (int group = 0; group < groups; group++) {
        int count = getInt(directOffset + 8 + 4L * group);
        require(count > 0, "Empty or negative group");
        sum += count;
      }
      require(sum == deps, "Dependency group boundaries do not match count");
      var seen = new HashSet<Integer>();
      for (int dep = 0; dep < deps; dep++) {
        int key = getInt(directOffset + 8 + 4L * groups + 4L * dep);
        validateKey(key);
        require(seen.add(key), "Duplicate direct dependency");
      }
      int reverseCount = getInt(reverseOffset);
      require(
          reverseCount >= 0 && reverseLength == reverseBytes(reverseCount),
          "Invalid reverse dependency count");
      seen.clear();
      for (int dep = 0; dep < reverseCount; dep++) {
        int key = getInt(reverseOffset + 4 + 4L * dep);
        validateKey(key);
        require(seen.add(key), "Duplicate reverse dependency");
      }
      nextDirect = Math.addExact(directOffset, directLength);
      nextReverse = Math.addExact(reverseOffset, reverseLength);
    }
    require(nextDirect == reverseStart && nextReverse == end, "Unclaimed dependency data");
  }

  private void validateKey(int id) throws IOException {
    require(id >= 0 && id < keys.length && keys[id] != null, "Invalid canonical key handle");
  }

  DoneDeps directDeps(int index) {
    return new MappedDoneDeps(this, getLong(HEADER_BYTES + (long) index * RECORD_BYTES + 16));
  }

  List<SkyKey> reverseDeps(int index) {
    long offset = getLong(HEADER_BYTES + (long) index * RECORD_BYTES + 32);
    return new KeyList(this, offset + 4, getInt(offset));
  }

  static final class KeyList extends AbstractList<SkyKey> {
    private final MappedGraphStore store;
    private final long offset;
    private final int size;

    KeyList(MappedGraphStore store, long offset, int size) {
      this.store = store;
      this.offset = offset;
      this.size = size;
    }

    @Override
    public SkyKey get(int index) {
      java.util.Objects.checkIndex(index, size);
      return store.key(store.getInt(offset + 4L * index));
    }

    @Override
    public int size() {
      return size;
    }
  }

  int getInt(long offset) {
    return data.get(INT, offset);
  }

  private long getLong(long offset) {
    return data.get(LONG, offset);
  }

  SkyKey key(int id) {
    return keys[id];
  }

  @Override
  long mappedBytes() {
    return data.byteSize();
  }

  @Override
  Path backingFile() {
    return file;
  }

  void prefault() {
    long witness = 0;
    for (long offset = 0; offset < data.byteSize(); offset += 4096) {
      witness += data.get(ValueLayout.JAVA_BYTE, offset);
    }
    prefaultWitness = witness;
  }

  void recordMaterialization(int count) {
    materializedDependencies.add(count);
  }

  long materializedDependencyCount() {
    return materializedDependencies.sum();
  }

  boolean isReadOnlyForTesting() {
    return data.isReadOnly();
  }

  SkyKey[] keyTableForTesting() {
    return keys.clone();
  }

  String revisionForTesting() {
    return revision;
  }

  private static long directBytes(DoneDeps deps) {
    return Math.addExact(
        8, Math.multiplyExact(4L, (long) deps.groupCount() + deps.dependencyCount()));
  }

  private static long reverseBytes(int count) {
    return Math.addExact(4, Math.multiplyExact(4L, count));
  }

  private static void checkInterrupted() throws InterruptedException {
    if (Thread.interrupted()) {
      throw new InterruptedException();
    }
  }

  private static MessageDigest digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void require(boolean valid, String message) throws IOException {
    if (!valid) {
      throw new IOException(message);
    }
  }
}
