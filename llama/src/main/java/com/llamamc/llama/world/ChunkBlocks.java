package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

// The mutable blocks of one loaded chunk. Only its ChunkEntry touches it, under the entry's monitor. Readers get an
// immutable GeneratedChunk, which is built from the array on demand and cached until the next write.
final class ChunkBlocks {
    // Both the snapshot and the codec index palette entries with 16 bits, so a chunk cannot hold more states in use.
    private static final int MAX_PALETTE = 65_536;
    private static final int INITIAL_SLOTS = 16;

    private final ChunkPosition position;
    private final int minY;
    private final int height;
    // A slot is a palette entry. slotUses counts the blocks that hold it. A slot that no block holds joins
    // freeSlots and keeps its old state until it is reused, so the palette grows only when every entry is in use.
    private final List<BlockState> palette = new ArrayList<>();
    private final HashMap<BlockState, Integer> paletteIndexes = new HashMap<>();
    private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
    private int[] slotUses = new int[INITIAL_SLOTS];
    private final short[] indexes;
    private long revision;
    private boolean dirty;
    private IChunk snapshot;
    private long snapshotRevision = -1;

    private ChunkBlocks(ChunkPosition position, int minY, int height) {
        this.position = position;
        this.minY = minY;
        this.height = height;
        indexes = new short[IChunk.WIDTH * IChunk.WIDTH * height];
    }

    // Copies a loaded chunk. Runs once per chunk, at its first write, so untouched chunks keep only their snapshot.
    static ChunkBlocks copyOf(IChunk source) {
        var blocks = new ChunkBlocks(source.position(), source.minY(), source.height());
        for (var blockY = source.minY(); blockY < source.minY() + source.height(); blockY++) {
            for (var localZ = 0; localZ < IChunk.WIDTH; localZ++) {
                for (var localX = 0; localX < IChunk.WIDTH; localX++) {
                    var index = blocks.index(localX, blockY, localZ);
                    blocks.indexes[index] = (short) blocks.claimSlot(source.block(localX, blockY, localZ));
                }
            }
        }
        return blocks;
    }

    void set(int localX, int blockY, int localZ, BlockState state) {
        checkHorizontal(localX, localZ);
        checkVertical(blockY);
        var index = index(localX, blockY, localZ);
        var replaced = Short.toUnsignedInt(indexes[index]);
        // The store releases the replaced block's slot before the cap is checked. A write that replaces the only block
        // of a state therefore finds that slot free, so it is never refused.
        releaseSlot(replaced);
        if (!hasRoomFor(state)) {
            // A free slot would have made room, so the release freed nothing. Putting its use back undoes the release.
            slotUses[replaced]++;
            throw tooManyStates();
        }
        indexes[index] = (short) takeSlot(state);
        revision++;
        dirty = true;
    }

    long revision() {
        return revision;
    }

    boolean dirty() {
        return dirty;
    }

    // A save that took its snapshot at savedRevision cleared the change only if nothing was written since then.
    void markSaved(long savedRevision) {
        if (savedRevision == revision) {
            dirty = false;
        }
    }

    IChunk snapshot() {
        if (snapshotRevision != revision) {
            snapshot = new GeneratedChunk(position, minY, height, palette, indexes);
            snapshotRevision = revision;
        }
        return snapshot;
    }

    private int claimSlot(BlockState state) {
        if (!hasRoomFor(state)) {
            throw tooManyStates();
        }
        return takeSlot(state);
    }

    // The cap counts the slots in use. Free slots are reused first, so with none free every palette entry is in use.
    private boolean hasRoomFor(BlockState state) {
        return paletteIndexes.containsKey(state) || !freeSlots.isEmpty() || palette.size() < MAX_PALETTE;
    }

    // Counts one more block in the slot that holds state, giving state a slot first if it has none. Callers check
    // hasRoomFor before they take a slot.
    private int takeSlot(BlockState state) {
        var existing = paletteIndexes.get(state);
        if (existing != null) {
            slotUses[existing]++;
            return existing;
        }
        var slot = freeSlots.isEmpty() ? appendSlot(state) : reuseSlot(state);
        paletteIndexes.put(state, slot);
        slotUses[slot] = 1;
        return slot;
    }

    private int appendSlot(BlockState state) {
        palette.add(state);
        if (palette.size() > slotUses.length) {
            slotUses = Arrays.copyOf(slotUses, 2 * slotUses.length);
        }
        return palette.size() - 1;
    }

    private int reuseSlot(BlockState state) {
        int slot = freeSlots.pop();
        palette.set(slot, state);
        return slot;
    }

    // Removes one block from its slot. A slot that no block holds is no longer a state in use, so it becomes free.
    private void releaseSlot(int slot) {
        slotUses[slot]--;
        if (slotUses[slot] == 0) {
            paletteIndexes.remove(palette.get(slot));
            freeSlots.push(slot);
        }
    }

    private static IllegalStateException tooManyStates() {
        return new IllegalStateException("A chunk holds at most " + MAX_PALETTE + " block states in use");
    }

    private int index(int localX, int blockY, int localZ) {
        return (blockY - minY) * IChunk.WIDTH * IChunk.WIDTH + localZ * IChunk.WIDTH + localX;
    }

    private void checkHorizontal(int localX, int localZ) {
        if (localX >= 0 && localX < IChunk.WIDTH && localZ >= 0 && localZ < IChunk.WIDTH) {
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
