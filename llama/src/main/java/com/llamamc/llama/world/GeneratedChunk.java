package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;

import java.util.List;

public final class GeneratedChunk implements IChunk {
    private final ChunkPosition position;
    private final int minY;
    private final int height;
    private final List<BlockState> palette;
    private final byte[] blocks;

    public GeneratedChunk(ChunkPosition position, int minY, int height, List<BlockState> palette, byte[] blocks) {
        if (height <= 0 || height % 16 != 0) {
            throw new IllegalArgumentException("Chunk height must be a positive multiple of 16");
        }
        if (palette.isEmpty() || palette.size() > 256) {
            throw new IllegalArgumentException("Chunk palette must contain between 1 and 256 states");
        }
        if (blocks.length != WIDTH * WIDTH * height) {
            throw new IllegalArgumentException("Chunk block storage has the wrong size");
        }

        this.position = position;
        this.minY = minY;
        this.height = height;
        this.palette = List.copyOf(palette);
        this.blocks = blocks.clone();
    }

    @Override
    public ChunkPosition position() {
        return position;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public BlockState block(int localX, int blockY, int localZ) {
        checkHorizontal(localX, localZ);
        checkVertical(blockY);

        return palette.get(Byte.toUnsignedInt(blocks[index(localX, blockY, localZ)]));
    }

    @Override
    public int highestBlockY(int localX, int localZ) {
        checkHorizontal(localX, localZ);
        for (var blockY = minY + height - 1; blockY >= minY; blockY--) {
            if (!block(localX, blockY, localZ).equals(BlockState.AIR)) {
                return blockY;
            }
        }

        return minY - 1;
    }

    List<BlockState> palette() {
        return palette;
    }

    byte[] blocks() {
        return blocks.clone();
    }

    private int index(int localX, int blockY, int localZ) {
        return (blockY - minY) * WIDTH * WIDTH + localZ * WIDTH + localX;
    }

    private static void checkHorizontal(int localX, int localZ) {
        if (localX >= 0 && localX < WIDTH && localZ >= 0 && localZ < WIDTH) {
            return;
        }

        throw new IndexOutOfBoundsException("Chunk coordinates must be between 0 and 15");
    }

    private void checkVertical(int blockY) {
        if (blockY >= minY && blockY < minY + height) {
            return;
        }

        throw new IndexOutOfBoundsException("Chunk block Y must be between " + minY + " and " + (minY + height - 1));
    }
}
