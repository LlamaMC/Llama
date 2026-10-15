package com.llamamc.llamaapi.world;

@FunctionalInterface
public interface IChunkGenerator {
    IChunk generate(IWorld world, ChunkPosition position);

    default int generationVersion() {
        return 0;
    }
}
