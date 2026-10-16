package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

// The mutable blocks of one loaded chunk. Only its ChunkEntry touches it, under the entry's monitor. Readers get an
// immutable GeneratedChunk, which is built from the array on demand and cached until the next write.
final class ChunkBlocks {
    // Both the snapshot and the codec index palette entries with 16 bits, so a chunk cannot hold more states.
    private static final int MAX_PALETTE = 65_536;

    private final ChunkPosition position;
    private final int minY;
    private final int height;
    private final List<BlockState> palette = new ArrayList<>();
    private final HashMap<BlockState, Integer> paletteIndexes = new HashMap<>();
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
                    blocks.indexes[index] = (short) blocks.paletteIndex(source.block(localX, blockY, localZ));
                }
            }
        }
        return blocks;
    }

    void set(int localX, int blockY, int localZ, BlockState state) {
        checkHorizontal(localX, localZ);
        checkVertical(blockY);
        indexes[index(localX, blockY, localZ)] = (short) paletteIndex(state);
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

    private int paletteIndex(BlockState state) {
        var existing = paletteIndexes.get(state);
        if (existing != null) {
            return existing;
        }
        if (palette.size() == MAX_PALETTE) {
            throw new IllegalStateException("A chunk holds at most " + MAX_PALETTE + " block states");
        }
        palette.add(state);
        paletteIndexes.put(state, palette.size() - 1);
        return palette.size() - 1;
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
