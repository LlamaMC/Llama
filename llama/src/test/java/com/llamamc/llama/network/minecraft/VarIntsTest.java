package com.llamamc.llama.network.minecraft;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VarIntsTest {

    @Test
    void roundTripsMinecraftVarInts() {
        var values = new int[]{0, 1, 127, 128, 255, 2_097_151, Integer.MAX_VALUE, -1};

        for (var value : values) {
            var buffer = Unpooled.buffer();
            VarInts.write(buffer, value);
            assertEquals(value, VarInts.read(buffer));
            assertEquals(0, buffer.readableBytes());
            buffer.release();
        }
    }

    @Test
    void leavesIncompleteValuesForTheCallerToRetry() {
        var buffer = Unpooled.wrappedBuffer(new byte[]{(byte) 0x80});
        assertNull(VarInts.read(buffer));
        assertEquals(1, buffer.readableBytes());
        buffer.release();
    }
}
