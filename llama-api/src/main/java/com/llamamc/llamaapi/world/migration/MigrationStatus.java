package com.llamamc.llamaapi.world.migration;

public record MigrationStatus(State state, long completedChunks, long totalChunks, String detail) {
    public enum State {
        NOT_STARTED,
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED
    }
}
