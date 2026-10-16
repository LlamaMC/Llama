package com.llamamc.llamaapi.world;

import com.llamamc.llamaapi.concurrent.LlamaFuture;

import java.util.Optional;

public interface IWorld {

    String name();
    String seed();
    DimensionType dimension();
    WorldType type();

    /**
     * Returns the chunk only if it is already loaded. Never blocks and never starts a load.
     */
    Optional<IChunk> loadedChunk(int chunkX, int chunkZ);

    /**
     * Starts the load if it is not already running and returns a future. Never blocks the caller.
     * The future fails if the world is closed or too many loads are pending.
     * Do not call join() or await() on the returned future from a chunk generator or from any world worker thread,
     * because the pool that runs the load may be the one that is blocked.
     */
    LlamaFuture<IChunk> chunk(int chunkX, int chunkZ);

    /**
     * Blocks until the chunk is loaded. Throws IllegalStateException on the tick thread and on a world worker thread,
     * for example from inside a generator; use {@link #chunk} there. Do not call join() or await() on a future from
     * {@link #chunk} inside a chunk generator or on any world worker thread, because the pool that runs the load may be
     * the one that is blocked.
     */
    IChunk chunkNow(int chunkX, int chunkZ);

    default Optional<BlockState> peekBlock(int x, int y, int z) {
        return loadedChunk(Math.floorDiv(x, IChunk.WIDTH), Math.floorDiv(z, IChunk.WIDTH))
                // The contract is Optional, so an out-of-range Y is simply absent, not an exception.
                .filter(chunk -> y >= chunk.minY() && y < chunk.minY() + chunk.height())
                .map(chunk -> chunk.block(Math.floorMod(x, IChunk.WIDTH), y, Math.floorMod(z, IChunk.WIDTH)));
    }

}
