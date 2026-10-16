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
     * Removes a loaded chunk from memory. The chunk stays in storage, so the next chunk() call reloads it. Returns
     * false if the chunk is not loaded, still loading, held by a ticket, being evicted, or the world is closed. A chunk
     * that must be saved first is removed by a later eviction tick, and this call then returns false. Chunks that
     * callers already hold stay valid, because chunks are immutable.
     */
    boolean unloadChunk(int chunkX, int chunkZ);

    /**
     * Starts the load if it is not already running and returns a future. Never blocks the caller.
     * The future fails if the world is closed or too many loads are pending.
     * Do not call join() or await() on the returned future from a chunk generator or from any world worker thread,
     * because the pool that runs the load may be the one that is blocked.
     */
    LlamaFuture<IChunk> chunk(int chunkX, int chunkZ);

    /**
     * Loads the chunk if it is not loaded and returns a ticket once it is loaded. The chunk stays in memory until the
     * ticket is closed. Callable from any thread and never blocks. The future fails if the world is closed or too many
     * loads are pending. Do not call join() or await() on it from a chunk generator or from any world worker thread,
     * for the same reason as {@link #chunk}.
     */
    LlamaFuture<IChunkTicket> ticket(int chunkX, int chunkZ, ChunkTicketType type);

    /**
     * Blocks until the chunk is loaded. Throws IllegalStateException on the tick thread and on a world worker thread,
     * for example from inside a generator; use {@link #chunk} there. Do not call join() or await() on a future from
     * {@link #chunk} inside a chunk generator or on any world worker thread, because the pool that runs the load may be
     * the one that is blocked.
     */
    IChunk chunkNow(int chunkX, int chunkZ);

    /**
     * Sets one block. Runs on the tick thread only. Called from any other thread it throws IllegalStateException, and
     * the message names setBlockAsync, which is the call for other threads. The chunk that holds the block must already
     * be loaded: hold a ticket or wait for {@link #chunk} first. setBlock never loads a chunk, so a write to an
     * unloaded chunk throws IllegalStateException. A Y outside the chunk's vertical range throws
     * IndexOutOfBoundsException.
     */
    void setBlock(int x, int y, int z, BlockState state);

    /**
     * Queues {@link #setBlock} on the tick thread and returns a future that completes once the write has run. Callable
     * from any thread. The future fails with the same exceptions as setBlock, and it fails if the tick loop rejects the
     * task.
     */
    LlamaFuture<Void> setBlockAsync(int x, int y, int z, BlockState state);

    default Optional<BlockState> peekBlock(int x, int y, int z) {
        return loadedChunk(Math.floorDiv(x, IChunk.WIDTH), Math.floorDiv(z, IChunk.WIDTH))
                // The contract is Optional, so an out-of-range Y is simply absent, not an exception.
                .filter(chunk -> y >= chunk.minY() && y < chunk.minY() + chunk.height())
                .map(chunk -> chunk.block(Math.floorMod(x, IChunk.WIDTH), y, Math.floorMod(z, IChunk.WIDTH)));
    }

}
