package com.llamamc.llama.world;

import com.llamamc.llama.world.ChunkEntry.State;
import com.llamamc.llama.world.vicuna.VicunaChunkStorage;
import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.ChunkTicketType;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.IChunkGenerator;
import com.llamamc.llamaapi.world.WorldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class ChunkEvictionTest {
    private static final long IDLE_TIMEOUT = 1800;
    private static final long PRESSURE_IDLE_TIMEOUT = 600;
    private static final int BUDGET = 16;
    private static final IChunkSaveHook SAVE_NOW = chunk -> CompletableFuture.completedFuture(null);

    @TempDir
    Path directory;

    @Test
    void idleChunkIsUnloadedAfterTheIdleTimeoutAndReloadsOnAccess() throws Exception {
        var clock = new AtomicLong();
        var generations = new AtomicInteger();
        try (var scheduler = new ChunkScheduler("test");
             var world = world(countingGenerator(generations), scheduler, services(clock::get, false, SAVE_NOW))) {
            world.chunkNow(0, 0);

            world.evictionTick(PRESSURE_IDLE_TIMEOUT + 1, BUDGET);
            assertState(world, State.LOADED);

            world.evictionTick(IDLE_TIMEOUT, BUDGET);
            assertState(world, State.LOADED);

            world.evictionTick(IDLE_TIMEOUT + 1, BUDGET);
            assertState(world, State.EVICTING);

            world.evictionTick(IDLE_TIMEOUT + 2, BUDGET);
            assertTrue(world.stateOf(0, 0).isEmpty());
            assertTrue(world.loadedChunk(0, 0).isEmpty());

            clock.set(IDLE_TIMEOUT + 2);
            world.chunkNow(0, 0);
            assertState(world, State.LOADED);
            // The reload comes from storage, so the chunk is not generated a second time.
            assertEquals(1, generations.get());
        }
    }

    @Test
    void heldTicketKeepsTheChunkLoaded() throws Exception {
        var clock = new AtomicLong();
        try (var scheduler = new ChunkScheduler("test");
             var world = world(countingGenerator(new AtomicInteger()), scheduler, services(clock::get, false, SAVE_NOW))) {
            var ticket = world.ticket(0, 0, ChunkTicketType.PLAYER).join();

            world.evictionTick(IDLE_TIMEOUT + 5000, BUDGET);
            world.evictionTick(IDLE_TIMEOUT + 5001, BUDGET);
            assertState(world, State.LOADED);
            assertFalse(world.unloadChunk(0, 0));

            ticket.close();
        }
    }

    @Test
    void releasingTheTicketAllowsEvictionAfterAnIdlePeriodFromTheRelease() throws Exception {
        var clock = new AtomicLong();
        try (var scheduler = new ChunkScheduler("test");
             var world = world(countingGenerator(new AtomicInteger()), scheduler, services(clock::get, false, SAVE_NOW))) {
            var ticket = world.ticket(0, 0, ChunkTicketType.PLAYER).join();
            clock.set(100);
            ticket.close();

            world.evictionTick(100 + IDLE_TIMEOUT, BUDGET);
            assertState(world, State.LOADED);

            world.evictionTick(100 + IDLE_TIMEOUT + 1, BUDGET);
            assertState(world, State.EVICTING);

            world.evictionTick(100 + IDLE_TIMEOUT + 2, BUDGET);
            assertTrue(world.stateOf(0, 0).isEmpty());
        }
    }

    @Test
    void ticketAcquiredWhileEvictingCancelsTheEviction() throws Exception {
        var clock = new AtomicLong();
        try (var scheduler = new ChunkScheduler("test");
             var world = world(countingGenerator(new AtomicInteger()), scheduler, services(clock::get, false, SAVE_NOW))) {
            world.chunkNow(0, 0);
            world.evictionTick(IDLE_TIMEOUT + 1, BUDGET);
            assertState(world, State.EVICTING);

            clock.set(IDLE_TIMEOUT + 1);
            var ticket = world.ticket(0, 0, ChunkTicketType.PLUGIN).join();
            assertState(world, State.LOADED);

            // Without the ticket this tick would remove the chunk.
            world.evictionTick(IDLE_TIMEOUT + 2, BUDGET);
            assertState(world, State.LOADED);
            assertTrue(world.loadedChunk(0, 0).isPresent());

            ticket.close();
        }
    }

    @Test
    void theBudgetBoundsHowManyChunksOneTickExamines() throws Exception {
        var clock = new AtomicLong();
        try (var scheduler = new ChunkScheduler("test");
             var world = world(countingGenerator(new AtomicInteger()), scheduler, services(clock::get, false, SAVE_NOW))) {
            for (var x = 0; x < 10; x++) {
                world.chunkNow(x, 0);
            }
            assertEquals(10, world.entryCount());

            // Three chunks become EVICTING, and nothing is removed yet.
            world.evictionTick(IDLE_TIMEOUT + 1, 3);
            assertEquals(10, world.entryCount());

            // The three queued evictions finish and use up the whole budget, so no new chunk is examined.
            world.evictionTick(IDLE_TIMEOUT + 2, 3);
            assertEquals(7, world.entryCount());

            world.evictionTick(IDLE_TIMEOUT + 3, 3);
            assertEquals(7, world.entryCount());

            world.evictionTick(IDLE_TIMEOUT + 4, 3);
            assertEquals(4, world.entryCount());
        }
    }

    @Test
    void memoryPressureUsesTheShorterIdleTimeout() throws Exception {
        var clock = new AtomicLong();
        try (var scheduler = new ChunkScheduler("test");
             var world = world(countingGenerator(new AtomicInteger()), scheduler, services(clock::get, true, SAVE_NOW))) {
            world.chunkNow(0, 0);

            world.evictionTick(PRESSURE_IDLE_TIMEOUT, BUDGET);
            assertState(world, State.LOADED);

            world.evictionTick(PRESSURE_IDLE_TIMEOUT + 1, BUDGET);
            assertState(world, State.EVICTING);
        }
    }

    @Test
    void closingATicketTwiceReleasesItOnlyOnce() throws Exception {
        var clock = new AtomicLong();
        try (var scheduler = new ChunkScheduler("test");
             var world = world(countingGenerator(new AtomicInteger()), scheduler, services(clock::get, false, SAVE_NOW))) {
            var first = world.ticket(0, 0, ChunkTicketType.PLAYER).join();
            var second = world.ticket(0, 0, ChunkTicketType.SYSTEM).join();

            first.close();
            first.close();

            // The second ticket still holds the chunk, so a second release would have been visible here.
            world.evictionTick(IDLE_TIMEOUT + 5000, BUDGET);
            world.evictionTick(IDLE_TIMEOUT + 5001, BUDGET);
            assertState(world, State.LOADED);

            second.close();
        }
    }

    @Test
    void failedSaveKeepsTheChunkLoadedAndWaitsForAnotherIdlePeriod() throws Exception {
        var clock = new AtomicLong();
        var saves = new AtomicInteger();
        IChunkSaveHook failing = chunk -> {
            saves.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("disk full"));
        };
        try (var scheduler = new ChunkScheduler("test");
             var world = world(countingGenerator(new AtomicInteger()), scheduler, services(clock::get, false, failing))) {
            world.chunkNow(0, 0);

            world.evictionTick(IDLE_TIMEOUT + 1, BUDGET);
            world.evictionTick(IDLE_TIMEOUT + 2, BUDGET);
            assertState(world, State.LOADED);
            assertEquals(1, saves.get());

            world.evictionTick(IDLE_TIMEOUT + 3, BUDGET);
            assertState(world, State.LOADED);
            assertEquals(1, saves.get());

            // The failure restarted the idle period at tick IDLE_TIMEOUT + 2, so the next attempt comes after it.
            world.evictionTick(2 * IDLE_TIMEOUT + 3, BUDGET);
            assertState(world, State.EVICTING);
            assertEquals(2, saves.get());
        }
    }

    @Test
    void chunkStaysEvictingUntilASlowSaveCompletes() throws Exception {
        var clock = new AtomicLong();
        var save = new CompletableFuture<Void>();
        try (var scheduler = new ChunkScheduler("test");
             var world = world(countingGenerator(new AtomicInteger()), scheduler, services(clock::get, false, chunk -> save))) {
            world.chunkNow(0, 0);

            world.evictionTick(IDLE_TIMEOUT + 1, BUDGET);
            world.evictionTick(IDLE_TIMEOUT + 2, BUDGET);
            assertState(world, State.EVICTING);

            save.complete(null);
            world.evictionTick(IDLE_TIMEOUT + 3, BUDGET);
            assertTrue(world.stateOf(0, 0).isEmpty());
        }
    }

    private void assertState(LlamaWorld world, State expected) {
        assertEquals(Optional.of(expected), world.stateOf(0, 0));
    }

    private LlamaWorld world(IChunkGenerator generator, ChunkScheduler scheduler, ChunkServices services) {
        return new LlamaWorld("test", "1234", directory, DimensionType.OVERWORLD, WorldType.FLAT, generator,
                new VicunaChunkStorage(directory), scheduler, () -> false, services);
    }

    private static ChunkServices services(LongSupplier clock, boolean pressure, IChunkSaveHook saveHook) {
        return new ChunkServices(clock, () -> pressure, saveHook);
    }

    private static IChunkGenerator countingGenerator(AtomicInteger generations) {
        return (world, position) -> {
            generations.incrementAndGet();
            return simpleChunk(position);
        };
    }

    // A single-state chunk: enough to exercise loading, saving and eviction without a full generator.
    private static IChunk simpleChunk(ChunkPosition position) {
        var blocks = new byte[IChunk.WIDTH * IChunk.WIDTH * IChunk.WIDTH];
        return new GeneratedChunk(position, 0, IChunk.WIDTH, List.of(BlockState.AIR), blocks);
    }
}
