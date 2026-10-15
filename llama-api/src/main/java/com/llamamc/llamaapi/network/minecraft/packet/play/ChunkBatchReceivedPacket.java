package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record ChunkBatchReceivedPacket(float desiredChunksPerTick) implements IPacket {
}
