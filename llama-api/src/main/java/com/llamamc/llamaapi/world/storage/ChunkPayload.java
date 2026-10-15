package com.llamamc.llamaapi.world.storage;

import java.util.Arrays;

public record ChunkPayload(byte[] nbt) {
    public ChunkPayload {
        nbt = Arrays.copyOf(nbt, nbt.length);
    }

    @Override
    public byte[] nbt() {
        return Arrays.copyOf(nbt, nbt.length);
    }
}
