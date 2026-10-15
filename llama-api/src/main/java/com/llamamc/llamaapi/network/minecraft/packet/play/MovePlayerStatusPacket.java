package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record MovePlayerStatusPacket(boolean onGround, boolean horizontalCollision) implements IPacket {
}
