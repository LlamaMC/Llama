package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.IChunk;

import java.util.concurrent.CompletableFuture;

/**
 * Saves a chunk before it leaves memory. The chunk is removed only when the future completes normally. The hook must
 * not block and must not call back into the world.
 */
@FunctionalInterface
interface IChunkSaveHook {
    CompletableFuture<Void> save(IChunk chunk);
}
