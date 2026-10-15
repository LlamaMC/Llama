package com.llamamc.llamaapi.block;

public record BlockProperty(String name) {
    public BlockProperty {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Block property name cannot be blank");
        }
    }
}
