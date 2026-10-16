package com.llamamc.llama.world;

import com.llamamc.llama.config.WorldsConfig;
import com.llamamc.llama.world.vicuna.VicunaChunkStorage;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.IChunkGenerator;
import com.llamamc.llamaapi.world.IWorld;
import com.llamamc.llamaapi.world.IWorldManager;
import com.llamamc.llamaapi.world.WorldType;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

public final class WorldManager implements IWorldManager, AutoCloseable {
    private final Map<String, LlamaWorld> worlds;
    private final LlamaWorld mainWorld;
    private final ChunkScheduler scheduler;

    public WorldManager(WorldsConfig config, Path rootDirectory) {
        this(config, rootDirectory, () -> false);
    }

    public WorldManager(WorldsConfig config, Path rootDirectory, BooleanSupplier onTickThread) {
        var configuredWorlds = config.getWorlds();
        if (configuredWorlds == null || configuredWorlds.isEmpty()) {
            throw new IllegalArgumentException("At least one world must be configured");
        }

        scheduler = new ChunkScheduler("Shared");
        var loaded = new LinkedHashMap<String, LlamaWorld>();
        configuredWorlds.forEach((name, world) ->
                loaded.put(name, create(name, world, rootDirectory, scheduler, onTickThread)));
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

    @Override
    public void close() throws IOException {
        scheduler.close();
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
    }

    private static LlamaWorld create(String name, WorldsConfig.ConfigWorld config, Path rootDirectory,
                                     ChunkScheduler scheduler, BooleanSupplier onTickThread) {
        var directory = rootDirectory.resolve(name);
        var seed = config.getSeed() == null ? "0" : config.getSeed();
        var generator = generator(config.getType(), config.getDimension(), parseSeed(seed));
        return new LlamaWorld(name, seed, directory, config.getDimension(), config.getType(), generator,
                new VicunaChunkStorage(directory), scheduler, onTickThread);
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
