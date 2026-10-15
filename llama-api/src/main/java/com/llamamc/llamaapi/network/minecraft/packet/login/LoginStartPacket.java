package com.llamamc.llamaapi.network.minecraft.packet.login;

import com.llamamc.llamaapi.network.protocol.IPacket;

import java.util.UUID;

public record LoginStartPacket(String username, UUID profileId) implements IPacket {
}
