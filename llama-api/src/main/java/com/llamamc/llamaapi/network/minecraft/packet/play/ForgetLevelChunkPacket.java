package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;
import com.llamamc.llamaapi.world.ChunkPosition;

public record ForgetLevelChunkPacket(ChunkPosition position) implements IPacket {
    public ForgetLevelChunkPacket {
        if (position == null) {
            throw new IllegalArgumentException("position cannot be null");
        }
    }
}
