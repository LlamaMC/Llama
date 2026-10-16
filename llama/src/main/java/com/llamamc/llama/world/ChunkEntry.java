package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

// One chunk in the world map. State, ticket count, last access, blocks and the running save change only under the
// entry's monitor, so a ticket, an eviction and a write cannot interleave on the same chunk.
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
    // Null until the first write. Until then the loaded chunk is the current state.
    private ChunkBlocks blocks;
    // Set while a save is submitted and not finished. Completes after the save has updated the dirty flag.
    private CompletableFuture<Void> running;

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

    // The current state of a loaded chunk. Callers check that the chunk is loaded first.
    synchronized IChunk snapshot() {
        return blocks == null ? load.getNow(null) : blocks.snapshot();
    }

    synchronized long revision() {
        return blocks == null ? 0 : blocks.revision();
    }

    synchronized boolean dirty() {
        return blocks != null && blocks.dirty();
    }

    synchronized Optional<CompletableFuture<Void>> running() {
        return Optional.ofNullable(running);
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

    // Applies one write. Returns false if the chunk is UNLOADED or not loaded yet, and the caller reports that. A write
    // counts as an access, so it cancels an eviction in progress and an unsaved change is never left behind.
    synchronized boolean write(long tick, int localX, int blockY, int localZ, BlockState blockState) {
        if (state == State.UNLOADED || !isLoaded()) {
            return false;
        }
        if (blocks == null) {
            blocks = ChunkBlocks.copyOf(load.getNow(null));
        }
        blocks.set(localX, blockY, localZ, blockState);
        if (state == State.EVICTING) {
            cancelEviction();
        }
        lastAccessTick = tick;
        return true;
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
        state = State.EVICTING;
        saveOutcome = startSave(hook);
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

    // Autosave starts a save of the unsaved changes unless one is already running. It never waits.
    synchronized void autosave(Function<IChunk, CompletableFuture<Void>> writer) {
        if (running != null || blocks == null || !blocks.dirty()) {
            return;
        }
        submit(writer);
    }

    // Eviction may remove the chunk only after its newest change is written. A running save is waited for first,
    // because it may hold a snapshot from before that change, and a failed one is retried by this call.
    synchronized CompletableFuture<Void> saveBeforeUnload(Function<IChunk, CompletableFuture<Void>> writer) {
        if (running != null) {
            return running.handle((ignored, error) -> (Void) null)
                    .thenCompose(ignored -> saveBeforeUnload(writer));
        }
        if (blocks == null || !blocks.dirty()) {
            return CompletableFuture.completedFuture(null);
        }
        return submit(writer);
    }

    private boolean isLoaded() {
        return load.isDone() && !load.isCompletedExceptionally();
    }

    private void cancelEviction() {
        state = State.LOADED;
        saveOutcome = null;
    }

    private CompletableFuture<Throwable> startSave(IChunkSaveHook hook) {
        return saveOrFail(position, hook).handle((ignored, error) -> {
            if (error != null) {
                LOGGER.warn("Could not save chunk {}, so it stays loaded", position, error);
            }
            return error;
        });
    }

    private static CompletableFuture<Void> saveOrFail(ChunkPosition position, IChunkSaveHook hook) {
        CompletableFuture<Void> save;
        try {
            save = hook.save(position);
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }
        if (save == null) {
            return CompletableFuture.failedFuture(new NullPointerException("Save hook returned no future"));
        }
        return save;
    }

    // The snapshot is taken here, under the monitor, so the writer receives exactly the revision recorded below.
    private CompletableFuture<Void> submit(Function<IChunk, CompletableFuture<Void>> writer) {
        var snapshot = blocks.snapshot();
        var revision = blocks.revision();
        var done = new CompletableFuture<Void>();
        running = done;
        CompletableFuture<Void> write;
        try {
            write = writer.apply(snapshot);
        } catch (RuntimeException error) {
            write = CompletableFuture.failedFuture(error);
        }
        write.whenComplete((ignored, error) -> finishSave(done, revision, error));
        return done;
    }

    // Runs on the storage thread. Clears the running save before it completes the future, so a waiter sees the result.
    private synchronized void finishSave(CompletableFuture<Void> done, long revision, Throwable error) {
        running = null;
        if (error != null) {
            LOGGER.error("Could not save chunk {}, so it stays dirty", position, error);
            done.completeExceptionally(error);
            return;
        }
        blocks.markSaved(revision);
        done.complete(null);
    }
}
