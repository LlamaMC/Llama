package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class ChunkBlocksTest {
    private static final int SHORT_HEIGHT = 16;
    private static final int TALL_HEIGHT = 384;
    private static final int MIN_Y = -64;
    private static final int MAX_STATES = 65_536;
    private static final int EARLY_WRITES = 1_000;
    private static final int HISTORY_WRITES = 70_000;
    private static final int REFUSALS = 1_000;
    // Refusals are constant-time checks. A rescan per refusal would take seconds, so this bound is generous.
    private static final long REFUSAL_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(2);

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
    void aChunkAtTheCapRefusesNewStatesInConstantTime() {
        var blocks = fullChunk();
        var revision = blocks.revision();
        var started = System.nanoTime();

        // Each attempt writes a new state over an AIR block. AIR keeps other blocks, so the write needs a new slot.
        for (var refusal = 0; refusal < REFUSALS; refusal++) {
            var index = MAX_STATES - 1 + refusal;
            assertThrows(IllegalStateException.class, () -> setDistinct(blocks, index));
        }
        var elapsed = System.nanoTime() - started;

        assertEquals(revision, blocks.revision());
        assertEquals(BlockState.AIR, stateAt(blocks.snapshot(), MAX_STATES - 1));
        assertTrue(elapsed < REFUSAL_BUDGET_NANOS, "Refusals took " + elapsed + " ns");
    }

    @Test
    void overwritingTheOnlyBlockOfAStateAtTheCapIsAccepted() {
        var blocks = fullChunk();
        var replacement = distinctState(MAX_STATES);

        // Index 0 holds the only block of its state, so this write frees that state's slot before it needs one.
        place(blocks, 0, replacement);

        var snapshot = blocks.snapshot();
        assertEquals(replacement, stateAt(snapshot, 0));
        assertEquals(distinctState(1), stateAt(snapshot, 1));
        // The chunk is back at the cap, so one more new state is refused.
        assertThrows(IllegalStateException.class, () -> setDistinct(blocks, MAX_STATES + 1));
    }

    @Test
    void aFreeSlotIsReusedBeforeTheChunkTakesANewOne() {
        var blocks = fullChunk();
        // Writing AIR over the only block of state 0 frees its slot. AIR already has a slot, so this write needs none.
        place(blocks, 0, BlockState.AIR);

        // The chunk is at the cap, so this new state is accepted only because it reuses the freed slot.
        setDistinct(blocks, MAX_STATES - 1);

        var snapshot = blocks.snapshot();
        assertEquals(BlockState.AIR, stateAt(snapshot, 0));
        assertEquals(distinctState(MAX_STATES - 1), stateAt(snapshot, MAX_STATES - 1));
        // The reused slot is in use again, so the chunk is at the cap and one more new state is refused.
        assertThrows(IllegalStateException.class, () -> setDistinct(blocks, MAX_STATES));
    }

    // AIR fills every position the writes leave alone, so the states in use are AIR and the MAX_STATES - 1 distinct
    // states written here.
    private static ChunkBlocks fullChunk() {
        var blocks = ChunkBlocks.copyOf(airChunk(MIN_Y, TALL_HEIGHT));
        for (var index = 0; index < MAX_STATES - 1; index++) {
            setDistinct(blocks, index);
        }
        return blocks;
    }

    private static void setDistinct(ChunkBlocks blocks, int index) {
        place(blocks, index, distinctState(index));
    }

    private static void place(ChunkBlocks blocks, int index, BlockState state) {
        blocks.set(localX(index), blockY(index), localZ(index), state);
    }

    private static BlockState stateAt(IChunk chunk, int index) {
        return chunk.block(localX(index), blockY(index), localZ(index));
    }

    private static int localX(int index) {
        return index % IChunk.WIDTH;
    }

    private static int localZ(int index) {
        return index / IChunk.WIDTH % IChunk.WIDTH;
    }

    private static int blockY(int index) {
        return MIN_Y + index / (IChunk.WIDTH * IChunk.WIDTH);
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
