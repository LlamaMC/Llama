package com.llamamc.llamaapi.network.minecraft.packet.status;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record PingRequestPacket(long payload) implements IPacket {
}
