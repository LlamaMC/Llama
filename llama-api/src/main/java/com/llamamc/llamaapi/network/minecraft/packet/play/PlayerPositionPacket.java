package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record PlayerPositionPacket(int teleportId, double x, double y, double z, float yaw, float pitch) implements IPacket {
}
