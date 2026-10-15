package com.llamamc.llamaapi.network.minecraft.packet.common;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record KeepAlivePacket(long id) implements IPacket {
}
