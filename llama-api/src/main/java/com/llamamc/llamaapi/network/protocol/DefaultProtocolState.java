package com.llamamc.llamaapi.network.protocol;

public enum DefaultProtocolState implements IProtocolState {
    HANDSHAKE("handshake"),
    STATUS("status"),
    LOGIN("login"),
    PLAY("play");

    private final String id;

    DefaultProtocolState(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }
}
