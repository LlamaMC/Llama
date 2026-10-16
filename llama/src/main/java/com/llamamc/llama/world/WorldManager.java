package com.llamamc.llama.world;

import com.llamamc.llama.config.WorldsConfig;
import com.llamamc.llama.shutdown.ShutdownDeadline;
import com.llamamc.llama.world.vicuna.VicunaChunkStorage;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.IChunkGenerator;
import com.llamamc.llamaapi.world.IWorld;
import com.llamamc.llamaapi.world.IWorldManager;
import com.llamamc.llamaapi.world.WorldType;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

public final class WorldManager implements IWorldManager, AutoCloseable {
    // Per world and per tick. Bounds the idle scan, so the cost of one tick stays the same however many chunks are loaded.
    private static final int CHUNKS_EXAMINED_PER_TICK = 64;

    private final Map<String, LlamaWorld> worlds;
    private final LlamaWorld mainWorld;
    private final ChunkScheduler scheduler;
    private final LongSupplier tickClock;

    public WorldManager(WorldsConfig config, Path rootDirectory) {
        this(config, rootDirectory, () -> false, () -> 0L);
    }

    public WorldManager(WorldsConfig config, Path rootDirectory, BooleanSupplier onTickThread, LongSupplier tickClock) {
        var configuredWorlds = config.getWorlds();
        if (configuredWorlds == null || configuredWorlds.isEmpty()) {
            throw new IllegalArgumentException("At least one world must be configured");
        }

        scheduler = new ChunkScheduler("Shared");
        this.tickClock = tickClock;
        var loaded = new LinkedHashMap<String, LlamaWorld>();
        configuredWorlds.forEach((name, world) ->
                loaded.put(name, create(name, world, rootDirectory, scheduler, onTickThread, tickClock)));
        worlds = Collections.unmodifiableMap(loaded);
        mainWorld = loaded.getOrDefault("main", loaded.values().iterator().next());
    }

    @Override
    public Collection<IWorld> worlds() {
        return List.copyOf(worlds.values());
    }

    @Override
    public Optional<IWorld> world(String name) {
        return Optional.ofNullable(worlds.get(name));
    }

    @Override
    public IWorld mainWorld() {
        return mainWorld;
    }

    // Runs in the "chunks" tick phase, once per tick.
    public void tickChunks() {
        var tick = tickClock.getAsLong();
        for (var world : worlds.values()) {
            world.evictionTick(tick, CHUNKS_EXAMINED_PER_TICK);
        }
    }

    @Override
    public void close() throws IOException {
        close(ShutdownDeadline.BUDGET);
    }

    /**
     * Stops the chunk executors within the budget, then closes every world. The worlds are closed even when an
     * executor misses the budget, because their storage still has to be closed.
     *
     * @return {@code false} if an executor did not terminate within the budget
     */
    public boolean close(Duration budget) throws IOException {
        var terminated = scheduler.close(budget);
        IOException failure = null;
        for (var world : worlds.values()) {
            try {
                world.close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                    continue;
                }
                failure.addSuppressed(error);
            }
        }
        if (failure != null) {
            throw failure;
        }
        return terminated;
    }

    private static LlamaWorld create(String name, WorldsConfig.ConfigWorld config, Path rootDirectory,
                                     ChunkScheduler scheduler, BooleanSupplier onTickThread, LongSupplier tickClock) {
        var directory = rootDirectory.resolve(name);
        var seed = config.getSeed() == null ? "0" : config.getSeed();
        var generator = generator(config.getType(), config.getDimension(), parseSeed(seed));
        return new LlamaWorld(name, seed, directory, config.getDimension(), config.getType(), generator,
                new VicunaChunkStorage(directory), scheduler, onTickThread, ChunkServices.of(tickClock));
    }

    private static IChunkGenerator generator(WorldType type, DimensionType dimension, long seed) {
        if (dimension == DimensionType.VOID) {
            return new VoidChunkGenerator();
        }
        if (type == WorldType.FLAT) {
            return new FlatChunkGenerator();
        }
        if (dimension == DimensionType.OVERWORLD && type == WorldType.NORMAL) {
            return new NormalChunkGenerator(seed);
        }
        return new UnavailableVanillaGenerator();
    }

    private static long parseSeed(String seed) {
        try {
            return Long.parseLong(seed);
        } catch (NumberFormatException ignored) {
            return seed.hashCode();
        }
    }
}
