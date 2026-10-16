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
import java.util.concurrent.Executor;
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
        this(config, rootDirectory, () -> false, () -> 0L, LlamaWorld.NO_TICK_EXECUTOR);
    }

    /**
     * @param tickExecutor runs queued writes on the tick thread; a rejected task fails the write's future
     */
    public WorldManager(WorldsConfig config, Path rootDirectory, BooleanSupplier onTickThread, LongSupplier tickClock,
                        Executor tickExecutor) {
        var configuredWorlds = config.getWorlds();
        if (configuredWorlds == null || configuredWorlds.isEmpty()) {
            throw new IllegalArgumentException("At least one world must be configured");
        }

        scheduler = new ChunkScheduler("Shared");
        this.tickClock = tickClock;
        var loaded = new LinkedHashMap<String, LlamaWorld>();
        configuredWorlds.forEach((name, world) ->
                loaded.put(name, create(name, world, rootDirectory, scheduler, onTickThread, tickExecutor, tickClock)));
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
            world.autosaveTick(tick);
        }
    }

    @Override
    public void close() throws IOException {
        close(ShutdownDeadline.BUDGET);
    }

    /**
     * Saves the unsaved chunks of every world, stops the chunk executors, then closes every world. The saves run first
     * because they need the shared executors. The worlds are closed even when a save or an executor misses the budget,
     * because their storage still has to be closed. All of it shares one budget.
     *
     * @return {@code false} if a chunk was not saved or an executor did not terminate within the budget
     */
    public boolean close(Duration budget) throws IOException {
        if (budget == null) {
            throw new NullPointerException("budget");
        }
        var deadlineNanos = System.nanoTime() + budget.toNanos();
        var saved = true;
        for (var world : worlds.values()) {
            if (!world.flushDirtyChunks(remaining(deadlineNanos))) {
                saved = false;
            }
        }
        var terminated = scheduler.close(remaining(deadlineNanos));
        IOException failure = null;
        for (var world : worlds.values()) {
            try {
                world.closeResources();
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
        return saved && terminated;
    }

    private static Duration remaining(long deadlineNanos) {
        return Duration.ofNanos(Math.max(0, deadlineNanos - System.nanoTime()));
    }

    private static LlamaWorld create(String name, WorldsConfig.ConfigWorld config, Path rootDirectory,
                                     ChunkScheduler scheduler, BooleanSupplier onTickThread, Executor tickExecutor,
                                     LongSupplier tickClock) {
        var directory = rootDirectory.resolve(name);
        var seed = config.getSeed() == null ? "0" : config.getSeed();
        var generator = generator(config.getType(), config.getDimension(), parseSeed(seed));
        return new LlamaWorld(name, seed, directory, config.getDimension(), config.getType(), generator,
                new VicunaChunkStorage(directory), scheduler, onTickThread, tickExecutor, ChunkServices.of(tickClock));
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
