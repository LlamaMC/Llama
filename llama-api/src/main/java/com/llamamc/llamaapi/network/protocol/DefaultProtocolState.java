package com.llamamc.llamaapi.network.protocol;

public enum DefaultProtocolState implements IProtocolState {
    HANDSHAKE("handshake"),
    STATUS("status"),
    LOGIN("login"),
    CONFIGURATION("configuration"),
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
