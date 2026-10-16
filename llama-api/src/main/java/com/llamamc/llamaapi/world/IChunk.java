package com.llamamc.llamaapi.world;

/**
 * One chunk column. Reads are safe from any thread and do not change the chunk.
 * Coordinates are chunk-local: X and Z in [0, WIDTH), Y in [minY, minY + height).
 */
public interface IChunk {
    int WIDTH = 16;

    ChunkPosition position();
    int minY();
    int height();

    /**
     * Throws IndexOutOfBoundsException for any argument outside the ranges above.
     */
    BlockState block(int localX, int blockY, int localZ);

    /**
     * Throws IndexOutOfBoundsException for an X or Z outside [0, WIDTH).
     */
    int highestBlockY(int localX, int localZ);
}
