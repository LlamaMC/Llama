package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.IChunk;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

// Collaborators of a world's chunk lifecycle. Tests replace them to drive the clock, the memory pressure and the save.
record ChunkServices(LongSupplier tickClock, BooleanSupplier memoryPressure, IChunkSaveHook saveHook) {
    private static final double PRESSURE_RATIO = 0.85;
    // Nothing marks a chunk dirty yet, so there is nothing to write and the save completes at once.
    private static final IChunkSaveHook NOTHING_TO_SAVE = _ -> CompletableFuture.completedFuture(null);

    ChunkServices {
        if (tickClock == null) {
            throw new NullPointerException("tickClock");
        }
        if (memoryPressure == null) {
            throw new NullPointerException("memoryPressure");
        }
        if (saveHook == null) {
            throw new NullPointerException("saveHook");
        }
    }

    static ChunkServices of(LongSupplier tickClock) {
        return new ChunkServices(tickClock, ChunkServices::heapUnderPressure, NOTHING_TO_SAVE);
    }

    private static boolean heapUnderPressure() {
        var runtime = Runtime.getRuntime();
        var used = runtime.totalMemory() - runtime.freeMemory();
        return used > runtime.maxMemory() * PRESSURE_RATIO;
    }
}
