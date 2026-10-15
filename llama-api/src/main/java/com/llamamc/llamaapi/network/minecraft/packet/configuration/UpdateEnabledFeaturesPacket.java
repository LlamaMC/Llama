package com.llamamc.llamaapi.network.minecraft.packet.configuration;

import com.llamamc.llamaapi.network.protocol.IPacket;

import java.util.Set;

public record UpdateEnabledFeaturesPacket(Set<String> features) implements IPacket {
    public UpdateEnabledFeaturesPacket {
        features = Set.copyOf(features);
    }
}
