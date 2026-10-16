package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(30)
class ChunkBlocksTest {
    private static final int SHORT_HEIGHT = 16;
    private static final int TALL_HEIGHT = 384;
    private static final int MIN_Y = -64;
    private static final int OLD_CAP = 65_536;
    private static final int EARLY_WRITES = 1_000;
    private static final int HISTORY_WRITES = 70_000;

    @Test
    void replacedStatesDoNotCountTowardTheCapAndAnEarlierSnapshotKeepsItsBlocks() {
        var blocks = ChunkBlocks.copyOf(airChunk(0, SHORT_HEIGHT));
        // Every write uses a new state at one of 16 positions, so only 16 states are in use while the history grows.
        for (var index = 0; index < EARLY_WRITES; index++) {
            blocks.set(index % IChunk.WIDTH, 0, 0, distinctState(index));
        }
        var before = blocks.snapshot();
        var expected = readAll(before);

        for (var index = EARLY_WRITES; index < HISTORY_WRITES; index++) {
            blocks.set(index % IChunk.WIDTH, 0, 0, distinctState(index));
        }

        assertEquals(expected, readAll(before));
        var after = blocks.snapshot();
        for (var localX = 0; localX < IChunk.WIDTH; localX++) {
            assertEquals(distinctState(HISTORY_WRITES - IChunk.WIDTH + localX), after.block(localX, 0, 0));
        }
    }

    @Test
    void aChunkWhoseStatesInUseReachTheCapRefusesAnotherState() {
        var blocks = ChunkBlocks.copyOf(airChunk(MIN_Y, TALL_HEIGHT));
        // AIR still fills the rest of the chunk, so the states in use are the 65,535 written here plus AIR.
        for (var index = 0; index < OLD_CAP - 1; index++) {
            setDistinct(blocks, index);
        }

        assertThrows(IllegalStateException.class, () -> setDistinct(blocks, OLD_CAP - 1));
    }

    private static void setDistinct(ChunkBlocks blocks, int index) {
        var localX = index % IChunk.WIDTH;
        var localZ = index / IChunk.WIDTH % IChunk.WIDTH;
        var blockY = MIN_Y + index / (IChunk.WIDTH * IChunk.WIDTH);
        blocks.set(localX, blockY, localZ, distinctState(index));
    }

    private static GeneratedChunk airChunk(int minY, int height) {
        return new GeneratedChunk(new ChunkPosition(0, 0), minY, height, List.of(BlockState.AIR),
                new byte[IChunk.WIDTH * IChunk.WIDTH * height]);
    }

    // Each index gets its own state, so writing it adds a state the palette has not seen.
    private static BlockState distinctState(int index) {
        return new BlockState("minecraft:stone", Map.of("index", String.valueOf(index)));
    }

    private static List<BlockState> readAll(IChunk chunk) {
        var states = new ArrayList<BlockState>();
        for (var blockY = chunk.minY(); blockY < chunk.minY() + chunk.height(); blockY++) {
            for (var localZ = 0; localZ < IChunk.WIDTH; localZ++) {
                for (var localX = 0; localX < IChunk.WIDTH; localX++) {
                    states.add(chunk.block(localX, blockY, localZ));
                }
            }
        }
        return states;
    }
}
