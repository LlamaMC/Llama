package com.llamamc.llamaapi.world;

public interface IChunk {
    int WIDTH = 16;

    ChunkPosition position();
    int minY();
    int height();
    BlockState block(int localX, int blockY, int localZ);
    int highestBlockY(int localX, int localZ);
}
