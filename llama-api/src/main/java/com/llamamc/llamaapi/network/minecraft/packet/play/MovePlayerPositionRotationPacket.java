package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record MovePlayerPositionRotationPacket(double x, double y, double z, float yaw, float pitch,
                                               boolean onGround, boolean horizontalCollision) implements IPacket {
}
