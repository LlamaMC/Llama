package com.llamamc.llamaapi.world;

public record ChunkPosition(int x, int z) {
    public static final int REGION_SIZE = 32;

    public int regionX() {
        return Math.floorDiv(x, REGION_SIZE);
    }

    public int regionZ() {
        return Math.floorDiv(z, REGION_SIZE);
    }

    public int localX() {
        return Math.floorMod(x, REGION_SIZE);
    }

    public int localZ() {
        return Math.floorMod(z, REGION_SIZE);
    }

    public int regionIndex() {
        return localZ() * REGION_SIZE + localX();
    }
}
