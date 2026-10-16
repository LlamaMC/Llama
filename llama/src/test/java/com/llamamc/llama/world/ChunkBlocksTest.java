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
    private static final int MAX_STATES = 65_536;
    private static final int EARLY_WRITES = 1_000;
    private static final int HISTORY_WRITES = 70_000;
    private static final int REFUSALS = 1_000;
    private static final int CHURN_CYCLES = 1_000;
    // AIR plus SPARSE_STATES states make 199 entries. FREED_SLOTS is above the compaction floor and the first count
    // above half of them, so the loop that frees them stays below the trigger and only the store after it compacts.
    private static final int SPARSE_STATES = 198;
    private static final int FREED_SLOTS = 100;

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
        var scans = blocks.paletteScans();

        // Each attempt writes a new state over an AIR block. AIR keeps other blocks, so the write needs a new slot.
        for (var refusal = 0; refusal < REFUSALS; refusal++) {
            var index = MAX_STATES - 1 + refusal;
            assertThrows(IllegalStateException.class, () -> setDistinct(blocks, index));
        }

        // Only compaction scans the palette, and it never starts with no free slot. So refusals scan nothing.
        assertEquals(scans, blocks.paletteScans());
        assertEquals(revision, blocks.revision());
        assertEquals(BlockState.AIR, stateAt(blocks.snapshot(), MAX_STATES - 1));
    }

    @Test
    void freedSlotsAreCompactedOnTheNextStoreAndAnEarlierSnapshotKeepsItsBlocks() {
        var blocks = ChunkBlocks.copyOf(airChunk(MIN_Y, TALL_HEIGHT));
        for (var index = 0; index < SPARSE_STATES; index++) {
            setDistinct(blocks, index);
        }
        var before = blocks.snapshot();
        var expected = readAll(before);
        var scans = blocks.paletteScans();

        // Each write puts AIR over the only block of a state, so it frees a slot and reuses none. The palette is more
        // than half free after these writes, but nothing has compacted it yet.
        for (var index = 0; index < FREED_SLOTS; index++) {
            place(blocks, index, BlockState.AIR);
        }
        assertEquals(scans, blocks.paletteScans());
        assertEquals(SPARSE_STATES + 1, blocks.paletteSize());

        // AIR over AIR frees nothing, but the store still compacts first.
        place(blocks, SPARSE_STATES, BlockState.AIR);

        assertEquals(scans + 1, blocks.paletteScans());
        assertEquals(1 + SPARSE_STATES - FREED_SLOTS, blocks.paletteSize());
        assertEquals(expected, readAll(before));
        var after = blocks.snapshot();
        for (var index = 0; index < SPARSE_STATES; index++) {
            var expectedState = index < FREED_SLOTS ? BlockState.AIR : distinctState(index);
            assertEquals(expectedState, stateAt(after, index));
        }
    }

    @Test
    void smallPaletteWithChurnDoesNotScan() {
        var blocks = ChunkBlocks.copyOf(airChunk(MIN_Y, SHORT_HEIGHT));
        var scans = blocks.paletteScans();

        // Two positions take new states and go back to AIR, so each cycle frees two slots against a palette of three.
        // Free slots are reused first, so the palette stays at three entries and the freed slots never reach the floor.
        for (var cycle = 0; cycle < CHURN_CYCLES; cycle++) {
            place(blocks, 0, distinctState(2 * cycle));
            place(blocks, 1, distinctState(2 * cycle + 1));
            place(blocks, 0, BlockState.AIR);
            place(blocks, 1, BlockState.AIR);
        }

        assertEquals(scans, blocks.paletteScans());
        assertEquals(3, blocks.paletteSize());
        assertEquals(BlockState.AIR, stateAt(blocks.snapshot(), 0));
        assertEquals(BlockState.AIR, stateAt(blocks.snapshot(), 1));
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
