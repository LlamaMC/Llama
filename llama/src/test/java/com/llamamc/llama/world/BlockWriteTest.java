package com.llamamc.llama.world;

import com.llamamc.llama.world.vicuna.VicunaChunkStorage;
import com.llamamc.llamaapi.concurrent.LlamaFuture;
import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.IChunkGenerator;
import com.llamamc.llamaapi.world.WorldType;
import com.llamamc.llamaapi.world.storage.ChunkPayload;
import com.llamamc.llamaapi.world.storage.IChunkStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class BlockWriteTest {
    private static final long AUTOSAVE_TICKS = 600;
    private static final long IDLE_TIMEOUT = 1800;
    private static final int BUDGET = 16;
    private static final int BLOCK_X = 5;
    private static final int BLOCK_Y = 0;
    private static final int BLOCK_Z = 7;
    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final BlockState DIRT = new BlockState("minecraft:dirt");
    private static final int DISTINCT_STATES = 300;
    private static final int EARLY_WRITES = 1_000;
    private static final int HISTORY_WRITES = 70_000;

    @TempDir
    Path directory;

    @Test
    void setBlockOnTheTickThreadChangesTheBlockSeenByANewSnapshot() throws Exception {
        try (var scheduler = new ChunkScheduler("test");
             var world = world(scheduler, Thread.currentThread(), new VicunaChunkStorage(directory),
                     LlamaWorld.NO_TICK_EXECUTOR, new ChunkServices(() -> 0L, () -> false, null))) {
            world.chunk(0, 0).join();

            world.setBlock(BLOCK_X, BLOCK_Y, BLOCK_Z, STONE);

            assertEquals(STONE, world.loadedChunk(0, 0).orElseThrow().block(BLOCK_X, BLOCK_Y, BLOCK_Z));
        }
    }

    @Test
    void aSnapshotTakenBeforeAWriteKeepsTheOldBlock() throws Exception {
        try (var scheduler = new ChunkScheduler("test");
             var world = world(scheduler, Thread.currentThread(), new VicunaChunkStorage(directory),
                     LlamaWorld.NO_TICK_EXECUTOR, new ChunkServices(() -> 0L, () -> false, null))) {
            world.chunk(0, 0).join();
            var before = world.loadedChunk(0, 0).orElseThrow();

            world.setBlock(BLOCK_X, BLOCK_Y, BLOCK_Z, STONE);

            assertEquals(BlockState.AIR, before.block(BLOCK_X, BLOCK_Y, BLOCK_Z));
            assertEquals(STONE, world.loadedChunk(0, 0).orElseThrow().block(BLOCK_X, BLOCK_Y, BLOCK_Z));
        }
    }

    @Test
    void setBlockFromAnotherThreadThrowsAndNamesSetBlockAsync() throws Exception {
        try (var scheduler = new ChunkScheduler("test");
             var world = world(scheduler, Thread.currentThread(), new VicunaChunkStorage(directory),
                     LlamaWorld.NO_TICK_EXECUTOR, new ChunkServices(() -> 0L, () -> false, null))) {
            world.chunk(0, 0).join();

            var error = assertThrows(ExecutionException.class, () -> onOtherThread(() -> {
                world.setBlock(BLOCK_X, BLOCK_Y, BLOCK_Z, STONE);
                return null;
            }));

            assertInstanceOf(IllegalStateException.class, error.getCause());
            assertTrue(error.getCause().getMessage().contains("setBlockAsync"));
        }
    }

    @Test
    void setBlockAsyncFromAnotherThreadCompletesAfterTheTickRunsTheWrite() throws Exception {
        var queue = new LinkedBlockingQueue<Runnable>();
        Executor tickExecutor = queue::add;
        try (var scheduler = new ChunkScheduler("test");
             var world = world(scheduler, Thread.currentThread(), new VicunaChunkStorage(directory),
                     tickExecutor, new ChunkServices(() -> 0L, () -> false, null))) {
            world.chunk(0, 0).join();

            LlamaFuture<Void> write = onOtherThread(() -> world.setBlockAsync(BLOCK_X, BLOCK_Y, BLOCK_Z, STONE));
            assertFalse(write.isDone());

            // The test thread plays the tick loop: it runs the queued task, which is the write.
            var task = queue.poll(5, TimeUnit.SECONDS);
            assertNotNull(task);
            task.run();

            write.await();
            assertTrue(write.isDone());
            assertEquals(STONE, world.loadedChunk(0, 0).orElseThrow().block(BLOCK_X, BLOCK_Y, BLOCK_Z));
        }
    }

    @Test
    void writesOutsideALoadedChunkOrItsVerticalRangeAreRejected() throws Exception {
        try (var scheduler = new ChunkScheduler("test");
             var world = world(scheduler, Thread.currentThread(), new VicunaChunkStorage(directory),
                     LlamaWorld.NO_TICK_EXECUTOR, new ChunkServices(() -> 0L, () -> false, null))) {
            assertThrows(IllegalStateException.class, () -> world.setBlock(BLOCK_X, BLOCK_Y, BLOCK_Z, STONE));
            assertTrue(world.entryOf(0, 0).isEmpty());

            world.chunk(0, 0).join();
            assertThrows(IndexOutOfBoundsException.class, () -> world.setBlock(BLOCK_X, 400, BLOCK_Z, STONE));
        }
    }

    @Test
    void eachWriteIncrementsTheRevisionAndMarksTheChunkDirty() throws Exception {
        try (var scheduler = new ChunkScheduler("test");
             var world = world(scheduler, Thread.currentThread(), new VicunaChunkStorage(directory),
                     LlamaWorld.NO_TICK_EXECUTOR, new ChunkServices(() -> 0L, () -> false, null))) {
            world.chunk(0, 0).join();
            var entry = world.entryOf(0, 0).orElseThrow();
            assertEquals(0, entry.revision());
            assertFalse(entry.dirty());

            world.setBlock(BLOCK_X, BLOCK_Y, BLOCK_Z, STONE);
            world.setBlock(BLOCK_X, BLOCK_Y, BLOCK_Z, DIRT);

            assertEquals(2, entry.revision());
            assertTrue(entry.dirty());
        }
    }

    @Test
    void aSaveClearsTheDirtyFlagWhenNothingChangedDuringIt() throws Exception {
        var storage = new GatedStorage(new VicunaChunkStorage(directory));
        try (var scheduler = new ChunkScheduler("test");
             var world = world(scheduler, Thread.currentThread(), storage,
                     LlamaWorld.NO_TICK_EXECUTOR, new ChunkServices(() -> 0L, () -> false, null))) {
            world.chunk(0, 0).join();
            world.setBlock(BLOCK_X, BLOCK_Y, BLOCK_Z, STONE);
            var entry = world.entryOf(0, 0).orElseThrow();

            storage.hold();
            world.autosaveTick(AUTOSAVE_TICKS);
            var saving = entry.running().orElseThrow();
            storage.release();
            saving.join();

            assertFalse(entry.dirty());
        }
    }

    @Test
    void aWriteDuringASaveKeepsTheChunkDirtyUntilTheNextSave() throws Exception {
        var storage = new GatedStorage(new VicunaChunkStorage(directory));
        try (var scheduler = new ChunkScheduler("test");
             var world = world(scheduler, Thread.currentThread(), storage,
                     LlamaWorld.NO_TICK_EXECUTOR, new ChunkServices(() -> 0L, () -> false, null))) {
            world.chunk(0, 0).join();
            world.setBlock(BLOCK_X, BLOCK_Y, BLOCK_Z, STONE);
            var entry = world.entryOf(0, 0).orElseThrow();

            storage.hold();
            world.autosaveTick(AUTOSAVE_TICKS);
            var saving = entry.running().orElseThrow();
            // The running save holds the snapshot of revision 1, so this write is newer than what it saves.
            world.setBlock(BLOCK_X + 1, BLOCK_Y, BLOCK_Z, DIRT);
            storage.release();
            saving.join();

            assertEquals(2, entry.revision());
            assertTrue(entry.dirty());

            world.autosaveTick(AUTOSAVE_TICKS * 2);
            entry.running().orElseThrow().join();

            assertFalse(entry.dirty());
        }
    }

    @Test
    void autosaveStartsASaveOfADirtyChunkAfterTheIntervalAndNotBefore() throws Exception {
        try (var scheduler = new ChunkScheduler("test");
             var world = world(scheduler, Thread.currentThread(), new VicunaChunkStorage(directory),
                     LlamaWorld.NO_TICK_EXECUTOR, new ChunkServices(() -> 0L, () -> false, null))) {
            world.chunk(0, 0).join();
            world.setBlock(BLOCK_X, BLOCK_Y, BLOCK_Z, STONE);
            var entry = world.entryOf(0, 0).orElseThrow();

            world.autosaveTick(AUTOSAVE_TICKS - 1);
            assertTrue(entry.running().isEmpty());
            assertTrue(entry.dirty());

            world.autosaveTick(AUTOSAVE_TICKS);
            entry.running().orElseThrow().join();

            assertFalse(entry.dirty());
        }
    }

    @Test
    void evictionOfADirtyChunkSavesItFirstAndTheReloadedChunkHasTheWrite() throws Exception {
        var generations = new AtomicInteger();
        IChunkGenerator counting = (world, position) -> {
            generations.incrementAndGet();
            return new FlatChunkGenerator().generate(world, position);
        };
        try (var scheduler = new ChunkScheduler("test");
             var world = world(scheduler, Thread.currentThread(), counting, new VicunaChunkStorage(directory),
                     LlamaWorld.NO_TICK_EXECUTOR, new ChunkServices(() -> 0L, () -> false, null))) {
            world.chunk(0, 0).join();
            world.setBlock(BLOCK_X, BLOCK_Y, BLOCK_Z, STONE);
            var entry = world.entryOf(0, 0).orElseThrow();

            world.evictionTick(IDLE_TIMEOUT + 1, BUDGET);
            entry.running().orElseThrow().join();
            finishEviction(world, IDLE_TIMEOUT + 2);

            assertTrue(world.loadedChunk(0, 0).isEmpty());
            var reloaded = world.chunk(0, 0).join();
            assertEquals(STONE, reloaded.block(BLOCK_X, BLOCK_Y, BLOCK_Z));
            assertEquals(1, generations.get());
        }
    }

    @Test
    void aChunkWithMoreThan256StatesSurvivesAnAutosaveAndAnEviction() throws Exception {
        try (var scheduler = new ChunkScheduler("test");
             var world = world(scheduler, Thread.currentThread(), new VicunaChunkStorage(directory),
                     LlamaWorld.NO_TICK_EXECUTOR, new ChunkServices(() -> 0L, () -> false, null))) {
            world.chunk(0, 0).join();
            for (var index = 0; index < DISTINCT_STATES; index++) {
                world.setBlock(localX(index), localY(index), localZ(index), distinctState(index));
            }
            var entry = world.entryOf(0, 0).orElseThrow();

            world.autosaveTick(AUTOSAVE_TICKS);
            entry.running().orElseThrow().join();
            world.evictionTick(IDLE_TIMEOUT + 1, BUDGET);
            finishEviction(world, IDLE_TIMEOUT + 2);

            var reloaded = world.chunk(0, 0).join();
            for (var index = 0; index < DISTINCT_STATES; index++) {
                assertEquals(distinctState(index), reloaded.block(localX(index), localY(index), localZ(index)));
            }
        }
    }

    // Every write replaces a state at one of 16 blocks, so the flat chunk uses at most 20 states (its 4 plus 16) while
    // its history passes the old 65,536-state cap. Writes must keep succeeding, and an earlier snapshot keeps its
    // blocks.
    @Test
    void writesKeepSucceedingPastTheOldCapWhenTheChunkUsesFewStates() throws Exception {
        try (var scheduler = new ChunkScheduler("test");
             var world = world(scheduler, Thread.currentThread(), new VicunaChunkStorage(directory),
                     LlamaWorld.NO_TICK_EXECUTOR, new ChunkServices(() -> 0L, () -> false, null))) {
            world.chunk(0, 0).join();
            for (var index = 0; index < EARLY_WRITES; index++) {
                world.setBlock(index % IChunk.WIDTH, BLOCK_Y, BLOCK_Z, distinctState(index));
            }
            var before = world.loadedChunk(0, 0).orElseThrow();
            var expected = row(before);

            for (var index = EARLY_WRITES; index < HISTORY_WRITES; index++) {
                world.setBlock(index % IChunk.WIDTH, BLOCK_Y, BLOCK_Z, distinctState(index));
            }

            assertEquals(expected, row(before));
            var after = world.loadedChunk(0, 0).orElseThrow();
            for (var localX = 0; localX < IChunk.WIDTH; localX++) {
                assertEquals(distinctState(HISTORY_WRITES - IChunk.WIDTH + localX),
                        after.block(localX, BLOCK_Y, BLOCK_Z));
            }
        }
    }

    // Each index gets its own block, so the chunk holds more than 256 distinct states and needs the wide index format.
    private static BlockState distinctState(int index) {
        return new BlockState("minecraft:stone", Map.of("index", String.valueOf(index)));
    }

    private static int localX(int index) {
        return index % IChunk.WIDTH;
    }

    private static int localZ(int index) {
        return index / IChunk.WIDTH % IChunk.WIDTH;
    }

    private static int localY(int index) {
        return index / (IChunk.WIDTH * IChunk.WIDTH);
    }

    // The 16 blocks that the history test writes to, read from one chunk.
    private static List<BlockState> row(IChunk chunk) {
        var states = new ArrayList<BlockState>();
        for (var localX = 0; localX < IChunk.WIDTH; localX++) {
            states.add(chunk.block(localX, BLOCK_Y, BLOCK_Z));
        }
        return states;
    }

    // Eviction finishes on a later tick, once the save it waits for has completed. Each pass only re-checks the chunk.
    private static void finishEviction(LlamaWorld world, long fromTick) {
        var tick = fromTick;
        while (world.stateOf(0, 0).isPresent()) {
            assertTrue(tick < fromTick + 100, "Eviction did not finish");
            world.evictionTick(tick, BUDGET);
            tick++;
        }
    }

    // Runs the task on a virtual thread, which is not the tick thread, and returns its result.
    private static <T> T onOtherThread(Callable<T> task) throws Exception {
        try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
            return caller.submit(task).get(5, TimeUnit.SECONDS);
        }
    }

    private LlamaWorld world(ChunkScheduler scheduler, Thread tickThread, IChunkStorage storage,
                             Executor tickExecutor, ChunkServices services) {
        return world(scheduler, tickThread, new FlatChunkGenerator(), storage, tickExecutor, services);
    }

    private LlamaWorld world(ChunkScheduler scheduler, Thread tickThread, IChunkGenerator generator,
                             IChunkStorage storage, Executor tickExecutor, ChunkServices services) {
        return new LlamaWorld("test", "1234", directory, DimensionType.OVERWORLD, WorldType.FLAT, generator, storage,
                scheduler, () -> Thread.currentThread() == tickThread, tickExecutor, services);
    }

    // Storage whose writes can be held until the test releases them. Holding applies to writes that start afterwards.
    private static final class GatedStorage implements IChunkStorage {
        private final IChunkStorage delegate;
        private volatile CountDownLatch gate;

        GatedStorage(IChunkStorage delegate) {
            this.delegate = delegate;
        }

        void hold() {
            gate = new CountDownLatch(1);
        }

        void release() {
            var held = gate;
            gate = null;
            if (held != null) {
                held.countDown();
            }
        }

        @Override
        public Optional<ChunkPayload> read(ChunkPosition position) throws IOException {
            return delegate.read(position);
        }

        @Override
        public void write(ChunkPosition position, ChunkPayload payload) throws IOException {
            awaitRelease();
            delegate.write(position, payload);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        // Bounded so a save that is never released fails the test instead of hanging it.
        private void awaitRelease() throws IOException {
            var held = gate;
            if (held == null) {
                return;
            }
            try {
                if (!held.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Write was never released");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(error);
            }
        }
    }
}
