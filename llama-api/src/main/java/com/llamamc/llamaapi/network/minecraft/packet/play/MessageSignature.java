package com.llamamc.llamaapi.network.minecraft.packet.play;

import java.util.Arrays;

public record MessageSignature(byte[] bytes) {
    public static final int BYTES = 256;

    public MessageSignature {
        if (bytes == null || bytes.length != BYTES) {
            throw new IllegalArgumentException("Message signature must be " + BYTES + " bytes");
        }

        bytes = Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public byte[] bytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof MessageSignature signature)) {
            return false;
        }

        return Arrays.equals(bytes, signature.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
