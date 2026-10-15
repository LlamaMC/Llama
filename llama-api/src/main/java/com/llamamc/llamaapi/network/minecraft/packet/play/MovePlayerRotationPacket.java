package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record MovePlayerRotationPacket(float yaw, float pitch, boolean onGround,
                                       boolean horizontalCollision) implements IPacket {
}
