package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.IChunkGenerator;
import com.llamamc.llamaapi.world.IWorld;

final class UnavailableVanillaGenerator implements IChunkGenerator {
    @Override
    public IChunk generate(IWorld world, ChunkPosition position) {
        throw new UnsupportedOperationException(
                "Exact Minecraft 26.3 " + world.type() + " generation is not implemented yet");
    }
}
