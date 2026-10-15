package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record SetChunkCacheCenterPacket(int chunkX, int chunkZ) implements IPacket {
}
