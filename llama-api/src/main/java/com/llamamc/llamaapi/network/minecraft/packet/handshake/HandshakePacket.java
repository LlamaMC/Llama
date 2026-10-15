package com.llamamc.llamaapi.network.minecraft.packet.handshake;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record HandshakePacket(int protocolVersion, String serverAddress, int serverPort, Intent intent) implements IPacket {
    public enum Intent {
        STATUS,
        LOGIN
    }
}
