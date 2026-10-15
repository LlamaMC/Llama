package com.llamamc.llamaapi.world;

import com.llamamc.llamaapi.registry.NamespacedKey;

import java.util.Map;

public record BlockState(NamespacedKey key, Map<String, String> properties) {
    public static final BlockState AIR = new BlockState("minecraft:air");

    public BlockState(String identifier) {
        this(NamespacedKey.parse(identifier), Map.of());
    }

    public BlockState(String identifier, Map<String, String> properties) {
        this(NamespacedKey.parse(identifier), properties);
    }

    public BlockState {
        properties = Map.copyOf(properties);
    }

    public String identifier() {
        return key.toString();
    }

    public String property(String name) {
        return properties.get(name);
    }

    public BlockState with(String name, String value) {
        var changed = new java.util.LinkedHashMap<>(properties);
        changed.put(name, value);
        return new BlockState(key, changed);
    }
}
