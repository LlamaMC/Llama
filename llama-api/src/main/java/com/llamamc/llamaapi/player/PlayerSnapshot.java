package com.llamamc.llamaapi.player;

import java.util.UUID;

public record PlayerSnapshot(UUID uuid, String name, String clientBrand) {
    public PlayerSnapshot {
        if (uuid == null) {
            throw new IllegalArgumentException("Snapshot UUID must not be null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Snapshot name must not be null");
        }
        if (clientBrand == null) {
            throw new IllegalArgumentException("Snapshot client brand must not be null");
        }
    }
}
