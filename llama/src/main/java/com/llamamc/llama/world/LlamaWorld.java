package com.llamamc.llama.world;

import com.llamamc.llamaapi.concurrent.LlamaFuture;
import com.llamamc.llamaapi.concurrent.LlamaPromise;
import com.llamamc.llamaapi.concurrent.LlamaPromise.LlamaAsyncException;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.IChunkGenerator;
import com.llamamc.llamaapi.world.IWorld;
import com.llamamc.llamaapi.world.WorldType;
import com.llamamc.llamaapi.world.storage.IChunkStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class LlamaWorld implements IWorld, AutoCloseable {
    private static final int MAX_PENDING_LOADS = 4096;

    private final String name;
    private final String seed;
    private final Path directory;
    private final DimensionType dimension;
    private final WorldType type;
    private final IChunkGenerator generator;
    private final IChunkStorage storage;
    private final ChunkNbtCodec codec = new ChunkNbtCodec();
    private final ConcurrentHashMap<ChunkPosition, CompletableFuture<IChunk>> chunks = new ConcurrentHashMap<>();
    private final AtomicInteger pendingLoads = new AtomicInteger();
    private final ExecutorService generationExecutor;
    private final ExecutorService storageExecutor;
    private final ChunkScheduler ownedScheduler;
    private final BooleanSupplier onTickThread;
    private volatile boolean closed;

    public LlamaWorld(String name, String seed, Path directory, DimensionType dimension, WorldType type,
                      IChunkGenerator generator, IChunkStorage storage) {
        this(name, seed, directory, dimension, type, generator, storage, new ChunkScheduler(name), true,
                () -> false);
    }

    LlamaWorld(String name, String seed, Path directory, DimensionType dimension, WorldType type,
               IChunkGenerator generator, IChunkStorage storage, ChunkScheduler scheduler,
               BooleanSupplier onTickThread) {
        this(name, seed, directory, dimension, type, generator, storage, scheduler, false, onTickThread);
    }

    private LlamaWorld(String name, String seed, Path directory, DimensionType dimension, WorldType type,
                       IChunkGenerator generator, IChunkStorage storage, ChunkScheduler scheduler,
                       boolean ownsScheduler, BooleanSupplier onTickThread) {
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
        var future = chunks.get(new ChunkPosition(chunkX, chunkZ));
        // isDone() comes first: once it is true the outcome cannot change, so the reads below cannot race a failure.
        if (future == null || !future.isDone() || future.isCompletedExceptionally()) {
            return Optional.empty();
        }
        return Optional.ofNullable(future.getNow(null));
    }

    @Override
    public boolean unloadChunk(int chunkX, int chunkZ) {
        // After close() the storage is gone, so an unloaded chunk could never be read back.
        if (closed) {
            return false;
        }
        var position = new ChunkPosition(chunkX, chunkZ);
        var future = chunks.get(position);
        if (future == null || !future.isDone() || future.isCompletedExceptionally()) {
            return false;
        }
        // The two-argument remove leaves the entry alone if another thread unloaded it and a reload replaced it.
        return chunks.remove(position, future);
    }

    @Override
    public LlamaFuture<IChunk> chunk(int chunkX, int chunkZ) {
        // Checked before the cache, so a chunk loaded before close() also fails afterwards.
        if (closed) {
            return failedFuture(new IllegalStateException("World is closed"));
        }
        var promise = new LlamaPromise<IChunk>();
        loadOrGenerate(new ChunkPosition(chunkX, chunkZ)).whenComplete((chunk, error) -> {
            if (error == null) {
                promise.complete(chunk);
                return;
            }
            promise.fail(unwrap(error));
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
        chunks.forEach((position, pending) -> {
            if (!pending.isDone()) {
                failLoad(position, pending, new IllegalStateException("World is closed"));
            }
        });
    }

    private static LlamaFuture<IChunk> failedFuture(Throwable error) {
        var promise = new LlamaPromise<IChunk>();
        promise.fail(error);
        return promise.future();
    }

    // Insert before loading: a concurrent caller joins this future instead of starting a second load,
    // and no IO runs while the map's bin lock is held.
    private CompletableFuture<IChunk> loadOrGenerate(ChunkPosition position) {
        var pending = new CompletableFuture<IChunk>();
        var existing = chunks.putIfAbsent(position, pending);
        if (existing != null) {
            return existing;
        }
        if (!reservePendingLoad()) {
            failLoad(position, pending, new IllegalStateException(
                    "Chunk loads are capped at " + MAX_PENDING_LOADS + " pending"));
            return pending;
        }
        pending.whenComplete((ignored, error) -> pendingLoads.decrementAndGet());
        try {
            startLoad(position, pending);
        } catch (RejectedExecutionException error) {
            failLoad(position, pending, error);
        }
        return pending;
    }

    private boolean reservePendingLoad() {
        if (pendingLoads.incrementAndGet() <= MAX_PENDING_LOADS) {
            return true;
        }
        pendingLoads.decrementAndGet();
        return false;
    }

    private void startLoad(ChunkPosition position, CompletableFuture<IChunk> pending) {
        CompletableFuture.supplyAsync(() -> load(position), storageExecutor)
                .thenCompose(result -> result.chunk() == null
                        ? CompletableFuture.supplyAsync(() -> generator.generate(this, position), generationExecutor)
                                .thenCompose(chunk -> CompletableFuture.supplyAsync(
                                        () -> write(position, chunk), storageExecutor))
                        : CompletableFuture.completedFuture(result.chunk()))
                .whenComplete((chunk, error) -> finishLoad(position, pending, chunk, error));
    }

    private void finishLoad(ChunkPosition position, CompletableFuture<IChunk> pending, IChunk chunk, Throwable error) {
        if (error != null) {
            failLoad(position, pending, error);
            return;
        }
        pending.complete(chunk);
    }

    private void failLoad(ChunkPosition position, CompletableFuture<IChunk> pending, Throwable error) {
        // The entry is removed first, so a caller that retries after seeing the failure starts a new load.
        chunks.remove(position, pending);
        pending.completeExceptionally(error);
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

    private record LoadResult(IChunk chunk) {
        private static final LoadResult MISSING = new LoadResult(null);
    }
}
