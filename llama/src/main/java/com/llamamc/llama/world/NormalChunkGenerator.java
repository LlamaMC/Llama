package com.llamamc.llama.world;

import com.llamamc.llama.world.generation.density.DensityFunction;
import com.llamamc.llama.world.generation.density.MinecraftDensityRouter;
import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.IChunkGenerator;
import com.llamamc.llamaapi.world.IWorld;

import java.util.List;
import java.util.Map;

public final class NormalChunkGenerator implements IChunkGenerator {
    private static final int GENERATION_VERSION = 1;
    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;
    private static final int SEA_LEVEL = 63;
    private static final int CELL_WIDTH = 4;
    private static final int CELL_HEIGHT = 8;
    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int WATER = 2;
    private static final int BEDROCK = 3;
    private static final int DIRT = 4;
    private static final int GRASS = 5;
    private static final int DEEPSLATE = 6;
    private static final int SAND = 7;
    private static final int SANDSTONE = 8;
    private static final List<BlockState> PALETTE = List.of(
            BlockState.AIR,
            new BlockState("minecraft:stone"),
            new BlockState("minecraft:water", Map.of("level", "0")),
            new BlockState("minecraft:bedrock"),
            new BlockState("minecraft:dirt"),
            new BlockState("minecraft:grass_block", Map.of("snowy", "false")),
            new BlockState("minecraft:deepslate", Map.of("axis", "y")),
            new BlockState("minecraft:sand"),
            new BlockState("minecraft:sandstone"));

    private final long seed;
    private final DensityFunction density;

    public NormalChunkGenerator(long seed) {
        this.seed = seed;
        density = new MinecraftDensityRouter(seed).overworld();
    }

    @Override
    public IChunk generate(IWorld world, ChunkPosition position) {
        var blocks = new byte[IChunk.WIDTH * IChunk.WIDTH * HEIGHT];
        var originX = position.x() * IChunk.WIDTH;
        var originZ = position.z() * IChunk.WIDTH;
        fillDensity(blocks, originX, originZ);
        for (var localZ = 0; localZ < IChunk.WIDTH; localZ++) {
            for (var localX = 0; localX < IChunk.WIDTH; localX++) {
                applySurface(blocks, localX, localZ);
            }
        }
        return new GeneratedChunk(position, MIN_Y, HEIGHT, PALETTE, blocks);
    }

    @Override
    public int generationVersion() {
        return GENERATION_VERSION;
    }

    private void fillDensity(byte[] blocks, int originX, int originZ) {
        for (var cellY = MIN_Y; cellY < MIN_Y + HEIGHT; cellY += CELL_HEIGHT) {
            for (var cellZ = 0; cellZ < IChunk.WIDTH; cellZ += CELL_WIDTH) {
                for (var cellX = 0; cellX < IChunk.WIDTH; cellX += CELL_WIDTH) {
                    fillCell(blocks, originX, originZ, cellX, cellY, cellZ);
                }
            }
        }
    }

    private void fillCell(byte[] blocks, int originX, int originZ, int cellX, int cellY, int cellZ) {
        for (var offsetY = 0; offsetY < CELL_HEIGHT; offsetY++) {
            var blockY = cellY + offsetY;
            for (var offsetZ = 0; offsetZ < CELL_WIDTH; offsetZ++) {
                var localZ = cellZ + offsetZ;
                var blockZ = originZ + localZ;
                for (var offsetX = 0; offsetX < CELL_WIDTH; offsetX++) {
                    var localX = cellX + offsetX;
                    var blockX = originX + localX;
                    blocks[index(localX, blockY, localZ)] = block(blockX, blockY, blockZ);
                }
            }
        }
    }

    private byte block(int blockX, int blockY, int blockZ) {
        if (isBedrock(blockX, blockY, blockZ)) {
            return BEDROCK;
        }
        if (density.sample(blockX, blockY, blockZ) > 0.0F) {
            return baseStone(blockX, blockY, blockZ);
        }
        return blockY <= SEA_LEVEL ? (byte) WATER : (byte) AIR;
    }

    private static void applySurface(byte[] blocks, int localX, int localZ) {
        var surfaceY = highestBaseStone(blocks, localX, localZ);
        if (surfaceY <= MIN_Y) {
            return;
        }

        var underwater = surfaceY <= SEA_LEVEL;
        var sandy = surfaceY >= SEA_LEVEL - 4 && surfaceY <= SEA_LEVEL + 1;
        blocks[index(localX, surfaceY, localZ)] = (byte) (sandy ? SAND : underwater ? DIRT : GRASS);
        for (var depth = 1; depth <= 3 && surfaceY - depth > MIN_Y; depth++) {
            var below = index(localX, surfaceY - depth, localZ);
            if (!isBaseStone(blocks[below])) {
                break;
            }
            blocks[below] = (byte) (sandy && depth == 3 ? SANDSTONE : sandy ? SAND : DIRT);
        }
    }

    private boolean isBedrock(int blockX, int blockY, int blockZ) {
        var depth = blockY - MIN_Y;
        if (depth == 0) {
            return true;
        }
        if (depth < 0 || depth >= 5) {
            return false;
        }
        return unitHash(blockX, blockY, blockZ) < (5.0 - depth) / 5.0;
    }

    private byte baseStone(int blockX, int blockY, int blockZ) {
        if (blockY <= 0) {
            return DEEPSLATE;
        }
        if (blockY >= 8) {
            return STONE;
        }
        return unitHash(blockX, blockY, blockZ) < (8.0 - blockY) / 8.0 ? (byte) DEEPSLATE : (byte) STONE;
    }

    private double unitHash(int blockX, int blockY, int blockZ) {
        var value = seed;
        value ^= blockX * 0x632BE59BD9B4E019L;
        value ^= blockY * 0x9E3779B97F4A7C15L;
        value ^= blockZ * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private static int highestBaseStone(byte[] blocks, int localX, int localZ) {
        for (var blockY = MIN_Y + HEIGHT - 1; blockY >= MIN_Y; blockY--) {
            if (isBaseStone(blocks[index(localX, blockY, localZ)])) {
                return blockY;
            }
        }
        return MIN_Y;
    }

    private static boolean isBaseStone(byte block) {
        return block == STONE || block == DEEPSLATE;
    }

    private static int index(int localX, int blockY, int localZ) {
        return (blockY - MIN_Y) * IChunk.WIDTH * IChunk.WIDTH + localZ * IChunk.WIDTH + localX;
    }
}
