package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record MovePlayerPositionPacket(double x, double y, double z, boolean onGround,
                                       boolean horizontalCollision) implements IPacket {
}
