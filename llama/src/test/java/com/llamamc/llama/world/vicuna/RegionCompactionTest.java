package com.llamamc.llama.world.vicuna;

import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.storage.ChunkPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Timeout(60)
class RegionCompactionTest {
    private static final int SECTOR = 4096;
    // One header sector and two directories of eight sectors each come before the data area.
    private static final long DATA_START = 17L * SECTOR;
    private static final long ONE_MIB = 1024L * 1024L;
    private static final int LARGE_PAYLOAD = 64 * 1024;
    private static final int LARGE_ROUNDS = 40;
    private static final long BLOCK_WAIT_NANOS = TimeUnit.SECONDS.toNanos(10);
    // The two commit slots follow the 32-byte header fields and are 24 bytes each.
    private static final long FIRST_COMMIT_OFFSET = 32L;
    private static final int COMMIT_SLOTS_BYTES = 48;
    private static final VicunaRegionFile.IFileMover MOVE_ATOMICALLY = (source, target) ->
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

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
            writeRounds(region, expected);
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
    void truncatedTemporaryFileLeftByACrashIsDeletedWhenTheRegionOpens() throws Exception {
        var path = regionPath();
        var expected = new byte[4][];
        try (var region = new VicunaRegionFile(path, 0, 0)) {
            writeRounds(region, expected);
        }

        // A crash during the copy leaves only the first part of the rewritten file behind.
        var temporary = temporaryPath();
        var bytes = Files.readAllBytes(path);
        Files.write(temporary, Arrays.copyOf(bytes, bytes.length / 2));

        try (var region = new VicunaRegionFile(path, 0, 0)) {
            assertFalse(Files.exists(temporary));
            assertChunks(region, expected);
        }
    }

    @Test
    void crashAfterTheMoveBeforeTheDirectorySyncReopensWithCompactedContent() throws Exception {
        var path = regionPath();
        var expected = new byte[4][];
        try (var region = new VicunaRegionFile(path, 0, 0, (source, target) -> {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            // Not an IOException, so compact() marks the region failed instead of reopening it; the object is abandoned as after a crash.
            throw new IllegalStateException("Simulated crash after the move");
        })) {
            writeRounds(region, expected);
            var sizeBefore = Files.size(path);

            assertThrows(IllegalStateException.class, region::compact);

            assertTrue(Files.size(path) < sizeBefore);
        }

        assertFalse(Files.exists(temporaryPath()));
        try (var region = new VicunaRegionFile(path, 0, 0)) {
            assertEquals(0L, region.deadBytes());
            assertChunks(region, expected);
        }
    }

    @Test
    void writeFromAnotherThreadWaitsForCompactionAndIsNotLost() throws Exception {
        var path = regionPath();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var expected = new byte[4][];
        var written = payload(5000, 99);
        try (var region = new VicunaRegionFile(path, 0, 0, (source, target) -> {
            entered.countDown();
            awaitLatch(release);
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        })) {
            writeRounds(region, expected);
            var sizeBefore = Files.size(path);
            var compaction = new FutureTask<Void>(() -> {
                region.compact();
                return null;
            });
            var write = new FutureTask<Void>(() -> {
                region.write(2, new ChunkPayload(written));
                return null;
            });

            Thread.ofPlatform().start(compaction);
            try {
                awaitMoveStarted(entered, compaction);
                awaitBlocked(Thread.ofPlatform().start(write), write);
                assertFalse(compaction.isDone());
            } finally {
                release.countDown();
            }

            compaction.get();
            write.get();
            expected[2] = written;
            assertTrue(Files.size(path) < sizeBefore);
            assertChunks(region, expected);
        }

        try (var region = new VicunaRegionFile(path, 0, 0)) {
            assertChunks(region, expected);
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
    void failedReopenAfterTheMoveRefusesReadsAndWrites() throws Exception {
        var path = regionPath();
        var expected = new byte[4][];
        var failNextOpen = new AtomicBoolean(false);
        try (var region = new VicunaRegionFile(path, 0, 0, MOVE_ATOMICALLY, failingOpenOnce(path, failNextOpen))) {
            writeRounds(region, expected);
            failNextOpen.set(true);

            var failure = assertThrows(IOException.class, region::compact);
            assertEquals("Simulated reopen failure", failure.getMessage());

            var read = assertThrows(IOException.class, () -> region.read(0));
            assertTrue(read.getMessage().contains("0,0"));
            assertSame(failure, read.getCause());
            var written = assertThrows(IOException.class,
                    () -> region.write(0, new ChunkPayload(payload(SECTOR, 9))));
            assertTrue(written.getMessage().contains("0,0"));
            assertThrows(IOException.class, region::compact);
            assertTrue(region.failed());
        }
    }

    @Test
    void reopeningFromDiskAfterTheSeamRecoversReadsEveryChunkCompacted() throws Exception {
        var path = regionPath();
        var expected = new byte[4][];
        var failNextOpen = new AtomicBoolean(false);
        try (var region = new VicunaRegionFile(path, 0, 0, MOVE_ATOMICALLY, failingOpenOnce(path, failNextOpen))) {
            writeRounds(region, expected);
            failNextOpen.set(true);
            assertThrows(IOException.class, region::compact);
        }

        // The seam has failed once and no longer does, so a region opened from the replaced file serves the compacted chunks.
        assertFalse(failNextOpen.get());
        try (var region = new VicunaRegionFile(path, 0, 0, MOVE_ATOMICALLY, failingOpenOnce(path, failNextOpen))) {
            assertFalse(region.failed());
            assertEquals(0L, region.deadBytes());
            assertFalse(Files.exists(temporaryPath()));
            assertChunks(region, expected);
        }
    }

    @Test
    void moverRuntimeFailureMarksTheRegionFailed() throws Exception {
        var path = regionPath();
        var expected = new byte[4][];
        try (var region = new VicunaRegionFile(path, 0, 0, (source, target) -> {
            throw new IllegalStateException("Simulated mover failure");
        })) {
            writeRounds(region, expected);

            assertThrows(IllegalStateException.class, region::compact);

            assertTrue(region.failed());
            assertFalse(Files.exists(temporaryPath()));
            var read = assertThrows(IOException.class, () -> region.read(0));
            assertTrue(read.getMessage().contains("0,0"));
        }
    }

    @Test
    void moveIOExceptionWithAnUncheckedFailedReopenMarksTheRegionFailed() throws Exception {
        var path = regionPath();
        var expected = new byte[4][];
        var failNextOpen = new AtomicBoolean(false);
        try (var region = new VicunaRegionFile(path, 0, 0, (source, target) -> {
            throw new IOException("Simulated rename failure");
        }, runtimeFailingOpenOnce(path, failNextOpen))) {
            writeRounds(region, expected);
            failNextOpen.set(true);

            var failure = assertThrows(IOException.class, region::compact);
            assertEquals("Simulated rename failure", failure.getMessage());
            assertEquals(1, failure.getSuppressed().length);
            assertEquals("Simulated opener failure", failure.getSuppressed()[0].getMessage());

            assertTrue(region.failed());
            assertFalse(Files.exists(temporaryPath()));
            var read = assertThrows(IOException.class, () -> region.read(0));
            assertTrue(read.getMessage().contains("0,0"));
            assertSame(failure, read.getCause());
            assertThrows(IOException.class, () -> region.write(0, new ChunkPayload(payload(SECTOR, 9))));
            assertThrows(IOException.class, region::compact);
        }
    }

    @Test
    void regionWithoutAValidCommitClosesEveryFailedChannel() throws Exception {
        var path = regionPath();
        try (var region = new VicunaRegionFile(path, 0, 0)) {
            region.write(0, new ChunkPayload(payload(SECTOR, 1)));
        }
        clearCommitSlots(path);

        var opened = new ArrayList<FileChannel>();
        var opener = recordingOpener(opened);
        assertThrows(IOException.class, () -> new VicunaRegionFile(path, 0, 0, MOVE_ATOMICALLY, opener));
        assertEquals(1, opened.size());
        assertFalse(opened.get(0).isOpen());

        assertThrows(IOException.class, () -> new VicunaRegionFile(path, 0, 0, MOVE_ATOMICALLY, opener));
        assertEquals(2, opened.size());
        assertFalse(opened.get(0).isOpen());
        assertFalse(opened.get(1).isOpen());
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

    @Test
    void writeStaysCommittedWhenCompactionThrowsAfterIt() throws Exception {
        var position = new ChunkPosition(0, 0);
        var moves = new AtomicInteger();
        try (var storage = new VicunaChunkStorage(directory, (source, target) -> {
            moves.incrementAndGet();
            throw new IllegalStateException("Simulated mover failure");
        }, FileChannel::open)) {
            for (var round = 0; round < LARGE_ROUNDS; round++) {
                storage.write(position, new ChunkPayload(payload(LARGE_PAYLOAD, round)));
                assertArrayEquals(payload(LARGE_PAYLOAD, round), storage.read(position).orElseThrow().nbt());
            }
        }

        // The mover ran at least once, so compaction was attempted after committed writes and every write still returned normally.
        assertTrue(moves.get() > 0);
        assertFalse(Files.exists(storedRegion().resolveSibling("r.0.0.vca.tmp")));
    }

    private Path regionPath() {
        return directory.resolve("r.0.0.vca");
    }

    private Path temporaryPath() {
        return directory.resolve("r.0.0.vca.tmp");
    }

    private Path storedRegion() {
        return directory.resolve("region").resolve("r.0.0.vca");
    }

    private static void writeRounds(VicunaRegionFile region, byte[][] expected) throws IOException {
        for (var round = 0; round < 5; round++) {
            for (var index = 0; index < expected.length; index++) {
                expected[index] = payload(1000 + index * 3000 + round, round * 10 + index);
                region.write(index, new ChunkPayload(expected[index]));
            }
        }
    }

    private static void assertChunks(VicunaRegionFile region, byte[][] expected) throws IOException {
        for (var index = 0; index < expected.length; index++) {
            assertArrayEquals(expected[index], region.read(index).orElseThrow().nbt());
        }
    }

    // The write must reach the region monitor that compact() holds, so it stays BLOCKED until the move returns.
    private static void awaitBlocked(Thread thread, Future<?> task) {
        var deadline = System.nanoTime() + BLOCK_WAIT_NANOS;
        while (thread.getState() != Thread.State.BLOCKED) {
            assertFalse(task.isDone(), "The write finished while compaction still held the region");
            assertTrue(System.nanoTime() < deadline, "The write never reached the region monitor");
            Thread.onSpinWait();
        }
    }

    // A compaction that fails before the move never counts the latch down, so its exception is surfaced instead of waiting forever.
    private static void awaitMoveStarted(CountDownLatch entered, Future<?> compaction) throws InterruptedException {
        if (entered.await(BLOCK_WAIT_NANOS, TimeUnit.NANOSECONDS)) {
            return;
        }

        if (!compaction.isDone()) {
            fail("The move never started");
        }

        try {
            compaction.get();
            fail("Compaction finished without reaching the move");
        } catch (ExecutionException error) {
            fail("Compaction failed before the move started: " + error.getCause(), error.getCause());
        }
    }

    // Fails only the first open of the region file itself, so the temporary file's open during copying is unaffected.
    private static VicunaRegionFile.IChannelOpener failingOpenOnce(Path target, AtomicBoolean failNext) {
        return (opened, options) -> {
            if (opened.equals(target) && failNext.getAndSet(false)) {
                throw new IOException("Simulated reopen failure");
            }

            return FileChannel.open(opened, options);
        };
    }

    // Fails only the first open of the region file itself with an unchecked exception, as a broken opener would.
    private static VicunaRegionFile.IChannelOpener runtimeFailingOpenOnce(Path target, AtomicBoolean failNext) {
        return (opened, options) -> {
            if (opened.equals(target) && failNext.getAndSet(false)) {
                throw new IllegalStateException("Simulated opener failure");
            }

            return FileChannel.open(opened, options);
        };
    }

    // Records every channel the region opens, so a test can check that a failed open closed its channel.
    private static VicunaRegionFile.IChannelOpener recordingOpener(List<FileChannel> opened) {
        return (target, options) -> {
            var channel = FileChannel.open(target, options);
            opened.add(channel);
            return channel;
        };
    }

    // Zeroes both commit slots, so the file has no committed directory left to open from.
    private static void clearCommitSlots(Path path) throws IOException {
        try (var file = FileChannel.open(path, StandardOpenOption.WRITE)) {
            file.write(ByteBuffer.allocate(COMMIT_SLOTS_BYTES), FIRST_COMMIT_OFFSET);
        }
    }

    private static void awaitLatch(CountDownLatch latch) throws IOException {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while the move was blocked", error);
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
