package com.llamamc.llamaapi.network.minecraft.packet.login;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.network.protocol.IPacket;

public record LoginDisconnectPacket(Component reason) implements IPacket {
    public LoginDisconnectPacket {
        if (reason == null) {
            throw new IllegalArgumentException("Login disconnect reason must not be null");
        }
    }
}
