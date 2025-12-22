package com.llamamc.llamaapi.network.protocol;

public interface IPacket<L> {
    PacketType type();
    void handle(L listener);

    default boolean terminal() {
        return false;
    }

    default boolean skippable() {
        return false;
    }
}
