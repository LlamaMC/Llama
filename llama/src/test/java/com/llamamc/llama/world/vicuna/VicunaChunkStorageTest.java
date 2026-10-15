package com.llamamc.llama.world.vicuna;

import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.storage.ChunkPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VicunaChunkStorageTest {
    @TempDir
    Path directory;

    @Test
    void commitsAndReopensChunks() throws Exception {
        var position = new ChunkPosition(-1, 32);
        try (var storage = new VicunaChunkStorage(directory)) {
            assertFalse(storage.read(position).isPresent());
            storage.write(position, new ChunkPayload(new byte[]{1, 2, 3}));
            storage.write(position, new ChunkPayload(new byte[]{4, 5}));
        }

        try (var storage = new VicunaChunkStorage(directory)) {
            var payload = storage.read(position);
            assertTrue(payload.isPresent());
            assertArrayEquals(new byte[]{4, 5}, payload.orElseThrow().nbt());
        }
    }

    @Test
    void separatesRegionCoordinates() throws Exception {
        var negative = new ChunkPosition(-1, -1);
        var positive = new ChunkPosition(31, 31);
        try (var storage = new VicunaChunkStorage(directory)) {
            storage.write(negative, new ChunkPayload(new byte[]{10}));
            storage.write(positive, new ChunkPayload(new byte[]{20}));
            assertArrayEquals(new byte[]{10}, storage.read(negative).orElseThrow().nbt());
            assertArrayEquals(new byte[]{20}, storage.read(positive).orElseThrow().nbt());
        }
    }
}
