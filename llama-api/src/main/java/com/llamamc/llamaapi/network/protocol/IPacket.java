package com.llamamc.llamaapi.network.protocol;

public interface IPacket {

    default boolean terminal() {
        return false;
    }

    default boolean skippable() {
        return false;
    }
}
