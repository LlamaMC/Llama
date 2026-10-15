package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.ChunkPosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NormalChunkGeneratorTest {
    @Test
    void generatesACompleteOverworldChunk() {
        var generator = new NormalChunkGenerator(123456789L);

        var chunk = generator.generate(null, new ChunkPosition(0, 0));

        assertEquals(-64, chunk.minY());
        assertEquals(384, chunk.height());
        assertEquals("minecraft:bedrock", chunk.block(0, -64, 0).key().toString());
        assertTrue(chunk.highestBlockY(8, 8) >= 63);
    }
}
