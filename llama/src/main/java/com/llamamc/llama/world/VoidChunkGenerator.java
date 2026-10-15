package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.IChunkGenerator;
import com.llamamc.llamaapi.world.IWorld;

import java.util.List;

public final class VoidChunkGenerator implements IChunkGenerator {
    @Override
    public IChunk generate(IWorld world, ChunkPosition position) {
        return new GeneratedChunk(position, -64, 384, List.of(BlockState.AIR), new byte[16 * 16 * 384]);
    }
}
