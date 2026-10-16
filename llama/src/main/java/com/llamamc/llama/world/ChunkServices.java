package com.llamamc.llama.world;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

// Collaborators of a world's chunk lifecycle. Tests replace them to drive the clock, the memory pressure and the save.
// A null save hook means the world saves its own dirty chunks, which is the production setting.
record ChunkServices(LongSupplier tickClock, BooleanSupplier memoryPressure, IChunkSaveHook saveHook) {
    private static final double PRESSURE_RATIO = 0.85;

    ChunkServices {
        if (tickClock == null) {
            throw new NullPointerException("tickClock");
        }
        if (memoryPressure == null) {
            throw new NullPointerException("memoryPressure");
        }
    }

    static ChunkServices of(LongSupplier tickClock) {
        return new ChunkServices(tickClock, ChunkServices::heapUnderPressure, null);
    }

    private static boolean heapUnderPressure() {
        var runtime = Runtime.getRuntime();
        var used = runtime.totalMemory() - runtime.freeMemory();
        return used > runtime.maxMemory() * PRESSURE_RATIO;
    }
}
