package com.llamamc.llama.network.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;

final class VarInts {
    private static final int MAX_BYTES = 5;

    private VarInts() {
    }

    static Integer read(ByteBuf input) {
        var readerIndex = input.readerIndex();
        var result = 0;

        for (var position = 0; position < MAX_BYTES; position++) {
            if (!input.isReadable()) {
                input.readerIndex(readerIndex);
                return null;
            }

            var value = input.readByte() & 0xFF;
            result |= (value & 0x7F) << position * 7;

            if ((value & 0x80) == 0) {
                return result;
            }
        }

        throw new DecoderException("VarInt is too large");
    }

    static void write(ByteBuf output, int value) {
        while ((value & ~0x7F) != 0) {
            output.writeByte(value & 0x7F | 0x80);
            value >>>= 7;
        }

        output.writeByte(value);
    }
}
