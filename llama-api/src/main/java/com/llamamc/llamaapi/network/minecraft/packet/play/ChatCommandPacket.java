package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record ChatCommandPacket(String command) implements IPacket {
    public ChatCommandPacket {
        if (command == null) {
            throw new IllegalArgumentException("Chat command must not be null");
        }
    }
}
