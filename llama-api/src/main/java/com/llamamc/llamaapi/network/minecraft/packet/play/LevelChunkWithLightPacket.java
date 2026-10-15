package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;
import com.llamamc.llamaapi.world.IChunk;

public record LevelChunkWithLightPacket(IChunk chunk) implements IPacket {
    public LevelChunkWithLightPacket {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk cannot be null");
        }
    }
}
