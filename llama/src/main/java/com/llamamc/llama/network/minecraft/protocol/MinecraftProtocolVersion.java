package com.llamamc.llama.network.minecraft.protocol;

import com.llamamc.llamaapi.network.protocol.PacketRegistry;
import com.llamamc.llama.registry.MinecraftRegistries;

public enum MinecraftProtocolVersion {
    V26_3("26.3", Minecraft26_3Protocol.VERSION);

    private final String name;
    private final int protocol;

    MinecraftProtocolVersion(String name, int protocol) {
        this.name = name;
        this.protocol = protocol;
    }

    public String nameValue() {
        return name;
    }

    public int protocol() {
        return protocol;
    }

    public PacketRegistry createRegistry() {
        return createRegistry(new MinecraftRegistries(this));
    }

    public PacketRegistry createRegistry(MinecraftRegistries registries) {
        return switch (this) {
            case V26_3 -> Minecraft26_3Protocol.createRegistry(registries);
        };
    }

    public static MinecraftProtocolVersion parse(String value) {
        for (var version : values()) {
            if (version.name.equals(value)) {
                return version;
            }
        }

        throw new IllegalArgumentException("Unsupported Minecraft protocol version: " + value);
    }
}
