package com.llamamc.llamaapi.network.protocol;

public enum PacketFlow {
    SERVERBOUND,
    CLIENTBOUND;

    public PacketFlow opposite() {
        return this == SERVERBOUND ? CLIENTBOUND : SERVERBOUND;
    }
}
