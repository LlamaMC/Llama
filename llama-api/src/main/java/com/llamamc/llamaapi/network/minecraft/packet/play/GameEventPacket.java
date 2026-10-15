package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record GameEventPacket(GameEvent event, float value) implements IPacket {
    public GameEventPacket(GameEvent event) {
        this(event, 0.0F);
    }
}
