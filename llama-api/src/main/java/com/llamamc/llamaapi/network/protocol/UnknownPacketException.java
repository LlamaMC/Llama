package com.llamamc.llamaapi.network.protocol;

public final class UnknownPacketException extends RuntimeException {
    public UnknownPacketException(IProtocolState state, PacketFlow flow, Object key) {
        super("Unknown " + flow + " packet in state '" + state.id() + "': " + key);
    }
}
