package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(30)
final class GeneratedChunkTest {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 16;
    private static final BlockState BEDROCK = new BlockState("minecraft:bedrock");

    @Test
    void rejectsYBelowMinY() {
        var chunk = chunk();

        assertThrows(IndexOutOfBoundsException.class, () -> chunk.block(0, MIN_Y - 1, 0));
    }

    @Test
    void rejectsYAtTheTopBoundary() {
        var chunk = chunk();

        assertThrows(IndexOutOfBoundsException.class, () -> chunk.block(0, MIN_Y + HEIGHT, 0));
    }

    @Test
    void returnsThePaletteStateAtMinY() {
        var chunk = chunk();

        assertSame(BEDROCK, chunk.block(0, MIN_Y, 0));
    }

    private static GeneratedChunk chunk() {
        var blocks = new byte[IChunk.WIDTH * IChunk.WIDTH * HEIGHT];
        Arrays.fill(blocks, (byte) 1);
        return new GeneratedChunk(new ChunkPosition(0, 0), MIN_Y, HEIGHT, List.of(BlockState.AIR, BEDROCK), blocks);
    }
}
