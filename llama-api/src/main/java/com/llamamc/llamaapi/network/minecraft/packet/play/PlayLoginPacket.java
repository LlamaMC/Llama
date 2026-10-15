package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record PlayLoginPacket(int playerId, int maxPlayers, int viewDistance, int simulationDistance) implements IPacket {
}
