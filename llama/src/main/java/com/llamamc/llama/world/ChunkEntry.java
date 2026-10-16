package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

// One chunk in the world map. State, ticket count and last access change only under the entry's monitor, so a ticket
// and an eviction cannot interleave on the same chunk.
final class ChunkEntry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkEntry.class);

    enum State {
        LOADED,
        EVICTING,
        UNLOADED
    }

    enum Settlement {
        REMOVED,
        PENDING,
        KEPT
    }

    private final ChunkPosition position;
    private final CompletableFuture<IChunk> load = new CompletableFuture<>();
    private State state = State.LOADED;
    private int tickets;
    private long lastAccessTick;
    // Set only while EVICTING. Completes normally with the save's failure, or with null once the save succeeded.
    private CompletableFuture<Throwable> saveOutcome;

    ChunkEntry(ChunkPosition position, long tick) {
        this.position = position;
        this.lastAccessTick = tick;
    }

    ChunkPosition position() {
        return position;
    }

    // The future is thread-safe and is completed once, so it is read without the monitor.
    CompletableFuture<IChunk> load() {
        return load;
    }

    synchronized State state() {
        return state;
    }

    // Returns false once the entry is UNLOADED, so the caller looks the chunk up again. Any access cancels an eviction
    // that is in progress.
    synchronized boolean access(long tick, boolean ticketed) {
        if (state == State.UNLOADED) {
            return false;
        }
        if (state == State.EVICTING) {
            cancelEviction();
        }
        if (ticketed) {
            tickets++;
        }
        lastAccessTick = tick;
        return true;
    }

    // The idle period starts at the release, so a chunk that was held for a long time is not evicted at once.
    synchronized void release(long tick) {
        tickets--;
        lastAccessTick = tick;
    }

    synchronized boolean beginEviction(long tick, long idleTimeout, IChunkSaveHook hook) {
        if (tick - lastAccessTick <= idleTimeout) {
            return false;
        }
        return beginUnload(hook);
    }

    // An explicit unload skips the idle check and follows the same rules otherwise: no tickets, loaded, and saved first.
    synchronized boolean beginUnload(IChunkSaveHook hook) {
        if (state != State.LOADED || tickets > 0 || !isLoaded()) {
            return false;
        }
        var chunk = load.getNow(null);
        state = State.EVICTING;
        saveOutcome = startSave(chunk, hook);
        return true;
    }

    // Finishes an eviction once its save is done. A failed save keeps the chunk loaded and starts a new idle period,
    // so the save is not retried on every tick.
    synchronized Settlement settle(long tick) {
        if (state != State.EVICTING) {
            return Settlement.KEPT;
        }
        if (!saveOutcome.isDone()) {
            return Settlement.PENDING;
        }
        if (saveOutcome.getNow(null) != null) {
            cancelEviction();
            lastAccessTick = tick;
            return Settlement.KEPT;
        }
        state = State.UNLOADED;
        saveOutcome = null;
        return Settlement.REMOVED;
    }

    private boolean isLoaded() {
        return load.isDone() && !load.isCompletedExceptionally();
    }

    private void cancelEviction() {
        state = State.LOADED;
        saveOutcome = null;
    }

    private CompletableFuture<Throwable> startSave(IChunk chunk, IChunkSaveHook hook) {
        return saveOrFail(chunk, hook).handle((ignored, error) -> {
            if (error != null) {
                LOGGER.warn("Could not save chunk {}, so it stays loaded", position, error);
            }
            return error;
        });
    }

    private static CompletableFuture<Void> saveOrFail(IChunk chunk, IChunkSaveHook hook) {
        CompletableFuture<Void> save;
        try {
            save = hook.save(chunk);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
        if (save == null) {
            return CompletableFuture.failedFuture(new NullPointerException("Save hook returned no future"));
        }
        return save;
    }
}
