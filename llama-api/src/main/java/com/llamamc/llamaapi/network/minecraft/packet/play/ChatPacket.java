package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

import java.time.Instant;

/**
 * Serverbound chat message. The signature is null when the client sent an unsigned message.
 */
public record ChatPacket(String message, Instant timestamp, long salt, MessageSignature signature,
                         LastSeenUpdate lastSeenMessages) implements IPacket {
    public ChatPacket {
        if (message == null) {
            throw new IllegalArgumentException("Chat message must not be null");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("Chat timestamp must not be null");
        }
        if (lastSeenMessages == null) {
            throw new IllegalArgumentException("Last seen messages must not be null");
        }
    }
}
