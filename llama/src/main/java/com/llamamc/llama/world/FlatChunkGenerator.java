package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.IChunkGenerator;
import com.llamamc.llamaapi.world.IWorld;

import java.util.List;

public final class FlatChunkGenerator implements IChunkGenerator {
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;
    private static final List<BlockState> PALETTE = List.of(
            BlockState.AIR,
            new BlockState("minecraft:bedrock"),
            new BlockState("minecraft:dirt"),
            new BlockState("minecraft:grass_block", java.util.Map.of("snowy", "false")));

    @Override
    public IChunk generate(IWorld world, ChunkPosition position) {
        var blocks = new byte[IChunk.WIDTH * IChunk.WIDTH * HEIGHT];
        fillLayer(blocks, 0, 1);
        fillLayer(blocks, 1, 2);
        fillLayer(blocks, 2, 2);
        fillLayer(blocks, 3, 3);
        return new GeneratedChunk(position, MIN_Y, HEIGHT, PALETTE, blocks);
    }

    private static void fillLayer(byte[] blocks, int layer, int paletteIndex) {
        var start = layer * IChunk.WIDTH * IChunk.WIDTH;
        java.util.Arrays.fill(blocks, start, start + IChunk.WIDTH * IChunk.WIDTH, (byte) paletteIndex);
    }
}
