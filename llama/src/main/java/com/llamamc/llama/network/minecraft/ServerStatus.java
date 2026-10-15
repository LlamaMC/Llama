package com.llamamc.llama.network.minecraft;

public record ServerStatus(String description, int maxPlayers) {

    public static final int PROTOCOL_VERSION = 777;
    public static final String VERSION_NAME = "26.3";

    public ServerStatus {
        if (description == null) {
            throw new IllegalArgumentException("description cannot be null");
        }
        if (maxPlayers < 0) {
            throw new IllegalArgumentException("maxPlayers cannot be negative");
        }
    }
}
