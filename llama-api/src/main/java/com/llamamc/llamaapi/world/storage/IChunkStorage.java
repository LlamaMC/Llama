package com.llamamc.llamaapi.world.storage;

import com.llamamc.llamaapi.world.ChunkPosition;

import java.io.IOException;
import java.util.Optional;

public interface IChunkStorage extends AutoCloseable {
    Optional<ChunkPayload> read(ChunkPosition position) throws IOException;
    void write(ChunkPosition position, ChunkPayload payload) throws IOException;

    @Override
    void close() throws IOException;
}
