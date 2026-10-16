package com.llamamc.llama.world.vicuna;

import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.storage.ChunkPayload;
import com.llamamc.llamaapi.world.storage.IChunkStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class VicunaChunkStorage implements IChunkStorage {
    private static final Logger LOGGER = LoggerFactory.getLogger(VicunaChunkStorage.class);

    private final Path directory;
    private final ConcurrentHashMap<RegionPosition, VicunaRegionFile> regions = new ConcurrentHashMap<>();

    public VicunaChunkStorage(Path worldDirectory) {
        directory = worldDirectory.resolve("region");
    }

    @Override
    public Optional<ChunkPayload> read(ChunkPosition position) throws IOException {
        return region(position).read(position.regionIndex());
    }

    @Override
    public void write(ChunkPosition position, ChunkPayload payload) throws IOException {
        var region = region(position);
        region.write(position.regionIndex(), payload);
        compactAfterWrite(region, position);
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (var region : regions.values()) {
            try {
                region.close();
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                    continue;
                }

                failure.addSuppressed(error);
            }
        }
        regions.clear();
        if (failure != null) {
            throw failure;
        }
    }

    // Runs on the IO worker that wrote the chunk. The write has already succeeded, so a failed compaction only logs.
    private static void compactAfterWrite(VicunaRegionFile region, ChunkPosition position) {
        try {
            region.compactIfNeeded();
        } catch (IOException error) {
            LOGGER.warn("Could not compact VICUÑA region {},{}", position.regionX(), position.regionZ(), error);
        }
    }

    private VicunaRegionFile region(ChunkPosition position) throws IOException {
        var regionPosition = new RegionPosition(position.regionX(), position.regionZ());
        try {
            return regions.computeIfAbsent(regionPosition, this::open);
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }
    }

    private VicunaRegionFile open(RegionPosition position) {
        var path = directory.resolve("r." + position.x() + "." + position.z() + ".vca");
        try {
            return new VicunaRegionFile(path, position.x(), position.z());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private record RegionPosition(int x, int z) {
    }
}
