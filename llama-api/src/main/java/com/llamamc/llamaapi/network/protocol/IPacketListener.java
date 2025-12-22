package com.llamamc.llamaapi.network.protocol;

public interface IPacketListener {
    void handle(IPacket<?> packet);

    default boolean shouldHandle(IPacket<?> packet) {
        return true;
    }

    default void tick() {}
}
