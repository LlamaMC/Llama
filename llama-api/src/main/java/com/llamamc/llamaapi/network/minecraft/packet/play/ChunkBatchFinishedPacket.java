package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record ChunkBatchFinishedPacket(int batchSize) implements IPacket {
}
