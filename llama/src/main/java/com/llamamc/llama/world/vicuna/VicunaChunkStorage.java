package com.llamamc.llama.world.vicuna;

import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.storage.ChunkPayload;
import com.llamamc.llamaapi.world.storage.IChunkStorage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class VicunaChunkStorage implements IChunkStorage {
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
        region(position).write(position.regionIndex(), payload);
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
