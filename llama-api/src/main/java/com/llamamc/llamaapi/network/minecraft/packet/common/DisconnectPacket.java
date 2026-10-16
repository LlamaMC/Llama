package com.llamamc.llamaapi.network.minecraft.packet.common;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.network.protocol.IPacket;

public record DisconnectPacket(Component reason) implements IPacket {
    public DisconnectPacket {
        if (reason == null) {
            throw new IllegalArgumentException("Disconnect reason must not be null");
        }
    }
}
