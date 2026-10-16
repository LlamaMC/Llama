package com.llamamc.llama.network.minecraft.protocol;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

final class ProtocolTestBuffers {
    private static final int VAR_INT_MAX_BYTES = 5;

    private ProtocolTestBuffers() {
    }

    static int readVarInt(ByteBuf input) {
        var result = 0;

        for (var position = 0; position < VAR_INT_MAX_BYTES; position++) {
            var value = input.readByte() & 0xFF;
            result |= (value & 0x7F) << position * 7;

            if ((value & 0x80) == 0) {
                return result;
            }
        }

        throw new IllegalStateException("Invalid VarInt");
    }

    static void writeVarInt(ByteBuf output, int value) {
        var remaining = value;

        while ((remaining & ~0x7F) != 0) {
            output.writeByte(remaining & 0x7F | 0x80);
            remaining >>>= 7;
        }

        output.writeByte(remaining);
    }

    static String readString(ByteBuf input) {
        var length = readVarInt(input);
        return input.readCharSequence(length, StandardCharsets.UTF_8).toString();
    }

    static void writeString(ByteBuf output, String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }
}
