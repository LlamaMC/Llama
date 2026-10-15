package com.llamamc.llamaapi.network.minecraft.packet.configuration;

import com.llamamc.llamaapi.network.protocol.IPacket;

import java.util.List;

public record RegistryDataPacket(String registry, List<Entry> entries) implements IPacket {
    public RegistryDataPacket {
        entries = List.copyOf(entries);
    }

    public record Entry(String identifier) {
    }
}
