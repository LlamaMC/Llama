package com.llamamc.llama.world;

import com.llamamc.llamaapi.concurrent.LlamaFuture;
import com.llamamc.llamaapi.concurrent.LlamaPromise;
import com.llamamc.llamaapi.concurrent.LlamaPromise.LlamaAsyncException;
import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.ChunkTicketType;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.IChunkGenerator;
import com.llamamc.llamaapi.world.IChunkTicket;
import com.llamamc.llamaapi.world.IWorld;
import com.llamamc.llamaapi.world.WorldType;
import com.llamamc.llamaapi.world.storage.IChunkStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class LlamaWorld implements IWorld, IChunkSaveHook, AutoCloseable {
    private static final int MAX_PENDING_LOADS = 4096;
    private static final long IDLE_TIMEOUT_TICKS = 1800;
    private static final long PRESSURE_IDLE_TIMEOUT_TICKS = 600;
    private static final long AUTOSAVE_INTERVAL_TICKS = 600;
    // Without a tick loop nothing advances, so every access is stamped with tick 0.
    private static final LongSupplier NO_TICK_LOOP = () -> 0L;
    // Without a tick loop no write can be queued, so setBlockAsync fails at once.
    static final Executor NO_TICK_EXECUTOR = task -> {
        throw new RejectedExecutionException("World has no tick loop");
    };

    private final String name;
    private final String seed;
    private final Path directory;
    private final DimensionType dimension;
    private final WorldType type;
    private final IChunkGenerator generator;
    private final IChunkStorage storage;
    private final ChunkNbtCodec codec = new ChunkNbtCodec();
    private final ConcurrentHashMap<ChunkPosition, ChunkEntry> chunks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<ChunkEntry> pendingEvictions = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingLoads = new AtomicInteger();
    private final ExecutorService generationExecutor;
    private final ExecutorService storageExecutor;
    private final ChunkScheduler ownedScheduler;
    private final BooleanSupplier onTickThread;
    private final Executor tickExecutor;
    private final LongSupplier tickClock;
    private final BooleanSupplier memoryPressure;
    private final IChunkSaveHook saveHook;
    // Tick thread only. Resumes the idle scan where the previous call stopped.
    private Iterator<ChunkEntry> sweep = Collections.emptyIterator();
    // Tick thread only. The next tick at which autosave starts its saves.
    private long nextAutosaveTick = AUTOSAVE_INTERVAL_TICKS;
    private volatile boolean closed;

    public LlamaWorld(String name, String seed, Path directory, DimensionType dimension, WorldType type,
                      IChunkGenerator generator, IChunkStorage storage) {
        this(name, seed, directory, dimension, type, generator, storage, new ChunkScheduler(name), true,
                () -> false, NO_TICK_EXECUTOR, ChunkServices.of(NO_TICK_LOOP));
    }

    LlamaWorld(String name, String seed, Path directory, DimensionType dimension, WorldType type,
               IChunkGenerator generator, IChunkStorage storage, ChunkScheduler scheduler,
               BooleanSupplier onTickThread) {
        this(name, seed, directory, dimension, type, generator, storage, scheduler, onTickThread,
                ChunkServices.of(NO_TICK_LOOP));
    }

    LlamaWorld(String name, String seed, Path directory, DimensionType dimension, WorldType type,
               IChunkGenerator generator, IChunkStorage storage, ChunkScheduler scheduler,
               BooleanSupplier onTickThread, ChunkServices services) {
        this(name, seed, directory, dimension, type, generator, storage, scheduler, onTickThread,
                NO_TICK_EXECUTOR, services);
    }

    // The executor runs setBlockAsync writes on the tick thread. A rejected task fails the write's future.
    LlamaWorld(String name, String seed, Path directory, DimensionType dimension, WorldType type,
               IChunkGenerator generator, IChunkStorage storage, ChunkScheduler scheduler,
               BooleanSupplier onTickThread, Executor tickExecutor, ChunkServices services) {
        this(name, seed, directory, dimension, type, generator, storage, scheduler, false, onTickThread,
                tickExecutor, services);
    }

    private LlamaWorld(String name, String seed, Path directory, DimensionType dimension, WorldType type,
                       IChunkGenerator generator, IChunkStorage storage, ChunkScheduler scheduler,
                       boolean ownsScheduler, BooleanSupplier onTickThread, Executor tickExecutor,
                       ChunkServices services) {
        this.name = name;
        this.seed = seed;
        this.directory = directory;
        this.dimension = dimension;
        this.type = type;
        this.generator = generator;
        this.storage = storage;
        generationExecutor = scheduler.generation();
        storageExecutor = scheduler.storage();
        ownedScheduler = ownsScheduler ? scheduler : null;
        this.onTickThread = onTickThread;
        this.tickExecutor = tickExecutor;
        tickClock = services.tickClock();
        memoryPressure = services.memoryPressure();
        // A null hook in the services means the world saves its own chunks.
        saveHook = services.saveHook() == null ? this : services.saveHook();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String seed() {
        return seed;
    }

    public Path directory() {
        return directory;
    }

    @Override
    public DimensionType dimension() {
        return dimension;
    }

    @Override
    public WorldType type() {
        return type;
    }

    @Override
    public Optional<IChunk> loadedChunk(int chunkX, int chunkZ) {
        var entry = chunks.get(new ChunkPosition(chunkX, chunkZ));
        if (entry == null || !entry.access(tickClock.getAsLong(), false)) {
            return Optional.empty();
        }
        var future = entry.load();
        // isDone() comes first: once it is true the outcome cannot change, so the reads below cannot race a failure.
        if (!future.isDone() || future.isCompletedExceptionally()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entry.snapshot());
    }

    @Override
    public void setBlock(int x, int y, int z, BlockState state) {
        if (state == null) {
            throw new NullPointerException("state");
        }
        if (!onTickThread.getAsBoolean()) {
            throw new IllegalStateException(
                    "setBlock() must run on the tick thread; use setBlockAsync() from other threads");
        }
        if (closed) {
            throw new IllegalStateException("World is closed");
        }
        var position = new ChunkPosition(Math.floorDiv(x, IChunk.WIDTH), Math.floorDiv(z, IChunk.WIDTH));
        var entry = chunks.get(position);
        var written = entry != null && entry.write(tickClock.getAsLong(), Math.floorMod(x, IChunk.WIDTH), y,
                Math.floorMod(z, IChunk.WIDTH), state);
        if (!written) {
            throw new IllegalStateException("Chunk " + position + " is not loaded; call chunk() or ticket() first");
        }
    }

    @Override
    public LlamaFuture<Void> setBlockAsync(int x, int y, int z, BlockState state) {
        if (state == null) {
            throw new NullPointerException("state");
        }
        var promise = new LlamaPromise<Void>();
        try {
            tickExecutor.execute(() -> {
                try {
                    setBlock(x, y, z, state);
                    promise.complete(null);
                } catch (RuntimeException error) {
                    promise.fail(error);
                }
            });
        } catch (RuntimeException error) {
            promise.fail(error);
        }
        return promise.future();
    }

    @Override
    public boolean unloadChunk(int chunkX, int chunkZ) {
        // After close() the storage is gone, so an unloaded chunk could never be read back.
        if (closed) {
            return false;
        }
        var entry = chunks.get(new ChunkPosition(chunkX, chunkZ));
        if (entry == null || !entry.beginUnload(saveHook)) {
            return false;
        }
        var settlement = finish(entry, tickClock.getAsLong());
        // A save that is still running is finished by a later eviction tick.
        if (settlement == ChunkEntry.Settlement.PENDING) {
            pendingEvictions.add(entry);
        }
        return settlement == ChunkEntry.Settlement.REMOVED;
    }

    @Override
    public LlamaFuture<IChunk> chunk(int chunkX, int chunkZ) {
        // Checked before the cache, so a chunk loaded before close() also fails afterwards.
        if (closed) {
            return failedFuture(new IllegalStateException("World is closed"));
        }
        var promise = new LlamaPromise<IChunk>();
        var entry = claim(new ChunkPosition(chunkX, chunkZ), false);
        entry.load().whenComplete((ignored, error) -> {
            if (error == null) {
                promise.complete(entry.snapshot());
                return;
            }
            promise.fail(unwrap(error));
        });
        return promise.future();
    }

    @Override
    public LlamaFuture<IChunkTicket> ticket(int chunkX, int chunkZ, ChunkTicketType type) {
        if (type == null) {
            throw new NullPointerException("type");
        }
        // Checked before the cache, for the same reason as in chunk().
        if (closed) {
            return failedFuture(new IllegalStateException("World is closed"));
        }
        var promise = new LlamaPromise<IChunkTicket>();
        var entry = claim(new ChunkPosition(chunkX, chunkZ), true);
        entry.load().whenComplete((chunk, error) -> {
            if (error != null) {
                // The caller never gets the ticket, so the hold taken for it is released here.
                entry.release(tickClock.getAsLong());
                promise.fail(unwrap(error));
                return;
            }
            promise.complete(new ChunkTicket(entry, tickClock));
        });
        return promise.future();
    }

    @Override
    public IChunk chunkNow(int chunkX, int chunkZ) {
        if (onTickThread.getAsBoolean()) {
            throw new IllegalStateException("chunkNow() would block the tick thread; use chunk() instead");
        }
        // An immediate throw, because a worker waiting on another queued chunk can starve the pool.
        if (Thread.currentThread() instanceof WorldWorkerThread) {
            throw new IllegalStateException("chunkNow() would block a world worker thread; use chunk() instead");
        }
        try {
            return chunk(chunkX, chunkZ).join();
        } catch (LlamaAsyncException error) {
            if (error.getCause() instanceof UncheckedIOException ioError) {
                throw ioError;
            }
            throw error;
        }
    }

    /**
     * Saves the unsaved changes of the chunk at the position. Completes at once when the chunk is clean. Eviction calls
     * this before it removes a chunk, so the future completes only after the save has succeeded.
     */
    @Override
    public CompletableFuture<Void> save(ChunkPosition position) {
        var entry = chunks.get(position);
        if (entry == null) {
            return CompletableFuture.completedFuture(null);
        }
        return entry.saveBeforeUnload(this::persist);
    }

    /**
     * Runs one eviction pass on the tick thread. Examines at most {@code budget} chunks: the evictions queued by earlier
     * calls first, then the map from where the previous scan stopped. Memory pressure is checked once per call.
     */
    void evictionTick(long tick, int budget) {
        if (closed || budget <= 0) {
            return;
        }
        var idleTimeout = memoryPressure.getAsBoolean() ? PRESSURE_IDLE_TIMEOUT_TICKS : IDLE_TIMEOUT_TICKS;
        var finished = finishPendingEvictions(tick, budget);
        scanForIdleChunks(tick, idleTimeout, budget - finished);
    }

    /**
     * Runs on the tick thread. Once every AUTOSAVE_INTERVAL_TICKS it starts a save of each dirty chunk. The saves run
     * on the storage executor, so the tick never waits for them. A chunk with a save running waits for the next round.
     */
    void autosaveTick(long tick) {
        if (closed || tick < nextAutosaveTick) {
            return;
        }
        nextAutosaveTick = tick + AUTOSAVE_INTERVAL_TICKS;
        for (var entry : chunks.values()) {
            entry.autosave(this::persist);
        }
    }

    // Package-private for tests. Reads the state without touching the chunk, so the read is not an access.
    Optional<ChunkEntry.State> stateOf(int chunkX, int chunkZ) {
        var entry = chunks.get(new ChunkPosition(chunkX, chunkZ));
        if (entry == null) {
            return Optional.empty();
        }
        return Optional.of(entry.state());
    }

    // Package-private for tests. The entry carries the revision, the dirty flag and the running save.
    Optional<ChunkEntry> entryOf(int chunkX, int chunkZ) {
        return Optional.ofNullable(chunks.get(new ChunkPosition(chunkX, chunkZ)));
    }

    // Package-private for tests.
    int entryCount() {
        return chunks.size();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (ownedScheduler != null) {
            ownedScheduler.close();
        }
        failPendingLoads();
        storage.close();
    }

    // Loads dropped from the queue during shutdown never run, so their waiters are failed here instead of hanging.
    private void failPendingLoads() {
        chunks.values().forEach(entry -> {
            if (!entry.load().isDone()) {
                failLoad(entry, new IllegalStateException("World is closed"));
            }
        });
    }

    private int finishPendingEvictions(long tick, int budget) {
        var examined = 0;
        var queued = pendingEvictions.size();
        while (examined < queued && examined < budget) {
            var entry = pendingEvictions.poll();
            if (entry == null) {
                break;
            }
            examined++;
            if (finish(entry, tick) == ChunkEntry.Settlement.PENDING) {
                pendingEvictions.add(entry);
            }
        }
        return examined;
    }

    private void scanForIdleChunks(long tick, long idleTimeout, int budget) {
        for (var examined = 0; examined < budget; examined++) {
            if (!sweep.hasNext()) {
                sweep = chunks.values().iterator();
                if (!sweep.hasNext()) {
                    return;
                }
            }
            var entry = sweep.next();
            if (entry.beginEviction(tick, idleTimeout, saveHook)) {
                pendingEvictions.add(entry);
            }
        }
    }

    private ChunkEntry.Settlement finish(ChunkEntry entry, long tick) {
        var settlement = entry.settle(tick);
        if (settlement == ChunkEntry.Settlement.REMOVED) {
            chunks.remove(entry.position(), entry);
        }
        return settlement;
    }

    private static <T> LlamaFuture<T> failedFuture(Throwable error) {
        var promise = new LlamaPromise<T>();
        promise.fail(error);
        return promise.future();
    }

    // Joins the entry in the map or starts the load of a new one, and records the access. An entry that is already
    // unloaded is dropped and replaced, so the caller always gets a live entry. The fresh entry is inserted before its
    // load starts, so a concurrent caller joins it instead of starting a second load, and no IO runs while the map's
    // bin lock is held.
    private ChunkEntry claim(ChunkPosition position, boolean ticketed) {
        var tick = tickClock.getAsLong();
        while (true) {
            var fresh = new ChunkEntry(position, tick);
            fresh.access(tick, ticketed);
            var existing = chunks.putIfAbsent(position, fresh);
            if (existing == null) {
                startLoad(fresh);
                return fresh;
            }
            if (existing.access(tick, ticketed)) {
                return existing;
            }
            chunks.remove(position, existing);
        }
    }

    private void startLoad(ChunkEntry entry) {
        if (!reservePendingLoad()) {
            failLoad(entry, new IllegalStateException(
                    "Chunk loads are capped at " + MAX_PENDING_LOADS + " pending"));
            return;
        }
        entry.load().whenComplete((ignored, error) -> pendingLoads.decrementAndGet());
        var position = entry.position();
        try {
            CompletableFuture.supplyAsync(() -> load(position), storageExecutor)
                    .thenCompose(result -> result.chunk() == null
                            ? CompletableFuture.supplyAsync(() -> generator.generate(this, position), generationExecutor)
                                    .thenCompose(chunk -> CompletableFuture.supplyAsync(
                                            () -> write(position, chunk), storageExecutor))
                            : CompletableFuture.completedFuture(result.chunk()))
                    .whenComplete((chunk, error) -> finishLoad(entry, chunk, error));
        } catch (RejectedExecutionException error) {
            failLoad(entry, error);
        }
    }

    private boolean reservePendingLoad() {
        if (pendingLoads.incrementAndGet() <= MAX_PENDING_LOADS) {
            return true;
        }
        pendingLoads.decrementAndGet();
        return false;
    }

    private void finishLoad(ChunkEntry entry, IChunk chunk, Throwable error) {
        if (error != null) {
            failLoad(entry, error);
            return;
        }
        entry.load().complete(chunk);
    }

    private void failLoad(ChunkEntry entry, Throwable error) {
        // The entry is removed first, so a caller that retries after seeing the failure starts a new load.
        chunks.remove(entry.position(), entry);
        entry.load().completeExceptionally(error);
    }

    private static Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }

    private LoadResult load(ChunkPosition position) {
        try {
            var payload = storage.read(position);
            if (payload.isEmpty()) {
                return LoadResult.MISSING;
            }
            return new LoadResult(codec.decode(payload.orElseThrow(), generator.generationVersion()));
        } catch (StaleChunkException ignored) {
            return LoadResult.MISSING;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private IChunk write(ChunkPosition position, IChunk chunk) {
        try {
            storage.write(position, codec.encode(chunk, generator.generationVersion()));
            return chunk;
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    // Writes one snapshot on the storage executor. Passed to the entries, which decide when a save is needed.
    private CompletableFuture<Void> persist(IChunk snapshot) {
        return CompletableFuture.runAsync(() -> write(snapshot.position(), snapshot), storageExecutor);
    }

    private record LoadResult(IChunk chunk) {
        private static final LoadResult MISSING = new LoadResult(null);
    }

    // Releases its hold on the chunk once, however often it is closed.
    private static final class ChunkTicket implements IChunkTicket {
        private final ChunkEntry entry;
        private final LongSupplier tickClock;
        private final AtomicBoolean released = new AtomicBoolean();

        ChunkTicket(ChunkEntry entry, LongSupplier tickClock) {
            this.entry = entry;
            this.tickClock = tickClock;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                entry.release(tickClock.getAsLong());
            }
        }
    }
}
