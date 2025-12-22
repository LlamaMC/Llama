package com.llamamc.llamaapi.network.protocol;

public record PacketType(PacketFlow flow, String id) {
    @Override
    public String toString() {
        return flow + "/" + id;
    }
}
