package com.llamamc.llama.world;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

public final class LlamaWorld implements IWorld, AutoCloseable {
    private final String name;
    private final String seed;
    private final Path directory;
    private final DimensionType dimension;
    private final WorldType type;
    private final IChunkGenerator generator;
    private final IChunkStorage storage;
    private final ChunkNbtCodec codec = new ChunkNbtCodec();
    private final ConcurrentHashMap<ChunkPosition, CompletableFuture<IChunk>> chunks = new ConcurrentHashMap<>();
    private final ExecutorService generationExecutor;
    private final ExecutorService storageExecutor;
    private final ChunkScheduler ownedScheduler;

    public LlamaWorld(String name, String seed, Path directory, DimensionType dimension, WorldType type,
                      IChunkGenerator generator, IChunkStorage storage) {
        this(name, seed, directory, dimension, type, generator, storage, new ChunkScheduler(name), true);
    }

    LlamaWorld(String name, String seed, Path directory, DimensionType dimension, WorldType type,
               IChunkGenerator generator, IChunkStorage storage, ChunkScheduler scheduler) {
        this(name, seed, directory, dimension, type, generator, storage, scheduler, false);
    }

    private LlamaWorld(String name, String seed, Path directory, DimensionType dimension, WorldType type,
                       IChunkGenerator generator, IChunkStorage storage, ChunkScheduler scheduler,
                       boolean ownsScheduler) {
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
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String seed() {
        return seed;
    }

    @Override
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
    public IChunk chunk(int chunkX, int chunkZ) {
        var position = new ChunkPosition(chunkX, chunkZ);
        try {
            return chunks.computeIfAbsent(position, this::loadOrGenerate).join();
        } catch (CompletionException error) {
            if (error.getCause() instanceof UncheckedIOException ioError) {
                throw ioError;
            }
            throw error;
        }
    }

    @Override
    public void close() throws IOException {
        if (ownedScheduler != null) {
            ownedScheduler.close();
        }
        storage.close();
    }

    private CompletableFuture<IChunk> loadOrGenerate(ChunkPosition position) {
        var future = CompletableFuture.supplyAsync(() -> load(position), storageExecutor)
                .thenCompose(result -> result.chunk() == null
                        ? CompletableFuture.supplyAsync(() -> generator.generate(this, position), generationExecutor)
                                .thenCompose(chunk -> CompletableFuture.supplyAsync(
                                        () -> write(position, chunk), storageExecutor))
                        : CompletableFuture.completedFuture(result.chunk()));
        future.whenComplete((ignored, error) -> {
            if (error != null) {
                chunks.remove(position, future);
            }
        });
        return future;
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
