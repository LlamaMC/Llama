package com.llamamc.llama.world.vicuna;

import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.storage.ChunkPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
class RegionCompactionTest {
    private static final int SECTOR = 4096;
    // One header sector and two directories of eight sectors each come before the data area.
    private static final long DATA_START = 17L * SECTOR;
    private static final long ONE_MIB = 1024L * 1024L;
    private static final int LARGE_PAYLOAD = 64 * 1024;
    private static final int LARGE_ROUNDS = 40;

    @TempDir
    Path directory;

    @Test
    void rewritingAChunkGrowsTheFileAndCountsDeadBytes() throws Exception {
        var path = regionPath();
        try (var region = new VicunaRegionFile(path, 0, 0)) {
            region.write(0, new ChunkPayload(payload(SECTOR, 1)));
            region.write(0, new ChunkPayload(payload(SECTOR, 2)));
            region.write(0, new ChunkPayload(payload(SECTOR, 3)));

            assertEquals(DATA_START + 3L * SECTOR, Files.size(path));
            assertEquals(2L * SECTOR, region.deadBytes());
        }

        try (var region = new VicunaRegionFile(path, 0, 0)) {
            assertEquals(2L * SECTOR, region.deadBytes());
            assertArrayEquals(payload(SECTOR, 3), region.read(0).orElseThrow().nbt());
        }
    }

    @Test
    void compactShrinksTheFileAndKeepsEveryChunk() throws Exception {
        var path = regionPath();
        var expected = new byte[4][];
        try (var region = new VicunaRegionFile(path, 0, 0)) {
            for (var round = 0; round < 5; round++) {
                for (var index = 0; index < expected.length; index++) {
                    expected[index] = payload(1000 + index * 3000 + round, round * 10 + index);
                    region.write(index, new ChunkPayload(expected[index]));
                }
            }
            var before = Files.size(path);
            assertTrue(region.deadBytes() > 0);

            region.compact();

            assertTrue(Files.size(path) < before);
            assertEquals(0L, region.deadBytes());
            assertChunks(region, expected);
        }

        try (var region = new VicunaRegionFile(path, 0, 0)) {
            assertEquals(0L, region.deadBytes());
            assertChunks(region, expected);
        }
    }

    @Test
    void leftoverTemporaryFileIsDeletedWhenTheRegionOpens() throws Exception {
        var path = regionPath();
        try (var region = new VicunaRegionFile(path, 0, 0)) {
            region.write(7, new ChunkPayload(payload(SECTOR, 1)));
        }

        // A complete rewrite with different data, left behind by a crash before the move.
        var rewritten = directory.resolve("scratch").resolve("r.0.0.vca");
        try (var region = new VicunaRegionFile(rewritten, 0, 0)) {
            region.write(7, new ChunkPayload(payload(SECTOR, 2)));
        }
        var temporary = directory.resolve("r.0.0.vca.tmp");
        Files.copy(rewritten, temporary);

        try (var region = new VicunaRegionFile(path, 0, 0)) {
            assertFalse(Files.exists(temporary));
            assertArrayEquals(payload(SECTOR, 1), region.read(7).orElseThrow().nbt());
        }
    }

    @Test
    void failedMoveKeepsTheOldFileReadable() throws Exception {
        var path = regionPath();
        var temporary = directory.resolve("r.0.0.vca.tmp");
        try (var region = new VicunaRegionFile(path, 0, 0, (source, target) -> {
            throw new IOException("Simulated rename failure");
        })) {
            region.write(0, new ChunkPayload(payload(SECTOR, 1)));
            region.write(0, new ChunkPayload(payload(SECTOR, 2)));
            region.write(1, new ChunkPayload(payload(SECTOR, 3)));
            var size = Files.size(path);

            assertThrows(IOException.class, region::compact);

            assertFalse(Files.exists(temporary));
            assertEquals(size, Files.size(path));
            assertArrayEquals(payload(SECTOR, 2), region.read(0).orElseThrow().nbt());
            assertArrayEquals(payload(SECTOR, 3), region.read(1).orElseThrow().nbt());

            region.write(2, new ChunkPayload(payload(SECTOR, 4)));
        }

        try (var region = new VicunaRegionFile(path, 0, 0)) {
            assertArrayEquals(payload(SECTOR, 2), region.read(0).orElseThrow().nbt());
            assertArrayEquals(payload(SECTOR, 4), region.read(2).orElseThrow().nbt());
        }
    }

    @Test
    void storageDoesNotCompactARegionSmallerThanOneMebibyte() throws Exception {
        var position = new ChunkPosition(0, 0);
        try (var storage = new VicunaChunkStorage(directory)) {
            for (var round = 0; round < 100; round++) {
                storage.write(position, new ChunkPayload(payload(SECTOR, round)));
            }
        }

        // Dead bytes are more than half of this file, but the file is under one mebibyte, so every write appended.
        assertEquals(DATA_START + 100L * SECTOR, Files.size(storedRegion()));
    }

    @Test
    void storageCompactsARegionOfAtLeastOneMebibyteWhenDeadBytesExceedHalf() throws Exception {
        var position = new ChunkPosition(0, 0);
        try (var storage = new VicunaChunkStorage(directory)) {
            for (var round = 0; round < LARGE_ROUNDS; round++) {
                storage.write(position, new ChunkPayload(payload(LARGE_PAYLOAD, round)));
            }
            assertArrayEquals(payload(LARGE_PAYLOAD, LARGE_ROUNDS - 1), storage.read(position).orElseThrow().nbt());
        }

        // Without compaction, forty payloads of 64 KiB would fill more than two mebibytes.
        assertTrue(Files.size(storedRegion()) < ONE_MIB);
    }

    private Path regionPath() {
        return directory.resolve("r.0.0.vca");
    }

    private Path storedRegion() {
        return directory.resolve("region").resolve("r.0.0.vca");
    }

    private static void assertChunks(VicunaRegionFile region, byte[][] expected) throws IOException {
        for (var index = 0; index < expected.length; index++) {
            assertArrayEquals(expected[index], region.read(index).orElseThrow().nbt());
        }
    }

    private static byte[] payload(int length, int seed) {
        var bytes = new byte[length];
        for (var index = 0; index < length; index++) {
            bytes[index] = (byte) (seed * 31 + index);
        }
        return bytes;
    }
}
