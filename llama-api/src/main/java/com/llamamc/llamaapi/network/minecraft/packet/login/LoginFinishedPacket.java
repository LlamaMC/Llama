package com.llamamc.llamaapi.network.minecraft.packet.login;

import com.llamamc.llamaapi.network.protocol.IPacket;

import java.util.List;
import java.util.UUID;

public record LoginFinishedPacket(UUID profileId, String username, List<Property> properties,
                                  UUID sessionId) implements IPacket {
    public LoginFinishedPacket {
        properties = List.copyOf(properties);
    }

    public record Property(String name, String value, String signature) {
    }
}
