package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

import java.time.Instant;
import java.util.List;

public record ChatCommandSignedPacket(String command, Instant timestamp, long salt,
                                      List<ArgumentSignature> argumentSignatures,
                                      LastSeenUpdate lastSeenMessages) implements IPacket {
    public ChatCommandSignedPacket {
        if (command == null) {
            throw new IllegalArgumentException("Chat command must not be null");
        }
        if (timestamp == null) {
            throw new IllegalArgumentException("Chat command timestamp must not be null");
        }
        if (argumentSignatures == null) {
            throw new IllegalArgumentException("Argument signatures must not be null");
        }
        if (lastSeenMessages == null) {
            throw new IllegalArgumentException("Last seen messages must not be null");
        }

        argumentSignatures = List.copyOf(argumentSignatures);
    }

    public record ArgumentSignature(String name, MessageSignature signature) {
        public ArgumentSignature {
            if (name == null) {
                throw new IllegalArgumentException("Argument name must not be null");
            }
            if (signature == null) {
                throw new IllegalArgumentException("Argument signature must not be null");
            }
        }
    }
}
