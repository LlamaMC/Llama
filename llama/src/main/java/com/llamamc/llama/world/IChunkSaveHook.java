package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.ChunkPosition;

import java.util.concurrent.CompletableFuture;

/**
 * Saves the unsaved changes of a chunk before it leaves memory. The chunk is removed only when the future completes
 * normally. The position is passed instead of a snapshot, because the hook must save the newest state, which can be
 * newer than any snapshot the eviction holds. The hook must not block and must not call back into the world.
 */
@FunctionalInterface
interface IChunkSaveHook {
    CompletableFuture<Void> save(ChunkPosition position);
}
