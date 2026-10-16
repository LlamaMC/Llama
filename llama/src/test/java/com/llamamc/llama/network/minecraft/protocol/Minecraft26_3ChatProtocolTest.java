package com.llamamc.llama.network.minecraft.protocol;

import com.llamamc.llamaapi.network.minecraft.packet.play.ChatCommandPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ChatCommandSignedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ChatPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.LastSeenUpdate;
import com.llamamc.llamaapi.network.minecraft.packet.play.MessageSignature;
import com.llamamc.llamaapi.network.protocol.DefaultProtocolState;
import com.llamamc.llamaapi.network.protocol.IPacket;
import com.llamamc.llamaapi.network.protocol.IProtocolState;
import com.llamamc.llamaapi.network.protocol.PacketFlow;
import com.llamamc.llamaapi.network.protocol.PacketRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

import static com.llamamc.llama.network.minecraft.protocol.ProtocolTestBuffers.readVarInt;
import static com.llamamc.llama.network.minecraft.protocol.ProtocolTestBuffers.writeString;
import static com.llamamc.llama.network.minecraft.protocol.ProtocolTestBuffers.writeVarInt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(10)
class Minecraft26_3ChatProtocolTest {
    // Source for every ID below: reports/reports/packets.json of the official 26.3 server, play serverbound.
    private static final int CHAT_COMMAND_ID = 7;
    private static final int CHAT_COMMAND_SIGNED_ID = 8;
    private static final int CHAT_ID = 9;
    private static final int LAST_SEEN_CHECKSUM = 0x2A;
    private static final int LAST_SEEN_WINDOW = 20;
    private static final int MAX_ARGUMENT_SIGNATURES = 8;
    private static final int MAX_CHAT_MESSAGE_CHARACTERS = 256;
    private static final IProtocolState STATE = DefaultProtocolState.PLAY;
    private static final PacketFlow FLOW = PacketFlow.SERVERBOUND;
    private static final Instant TIMESTAMP = Instant.ofEpochMilli(1_767_225_600_123L);
    private static final PacketRegistry REGISTRY = Minecraft26_3Protocol.createRegistry();

    @Test
    void registersTheChatAndCommandIdsFromTheOfficialReport() {
        assertEquals(CHAT_COMMAND_ID, encodedId(new ChatCommandPacket("help")));
        assertEquals(CHAT_COMMAND_SIGNED_ID,
                encodedId(new ChatCommandSignedPacket("help", TIMESTAMP, 0L, List.of(), lastSeen(0))));
        assertEquals(CHAT_ID, encodedId(new ChatPacket("hi", TIMESTAMP, 0L, null, lastSeen(0))));
    }

    @Test
    void roundTripsASignedChatMessageWithAcknowledgements() {
        assertRoundTrip(new ChatPacket("hello world", TIMESTAMP, 42L, signature(0x5A), lastSeen(5, 0, 3, 19)));
    }

    @Test
    void roundTripsAnUnsignedChatMessage() {
        assertRoundTrip(new ChatPacket("unsigned", TIMESTAMP, 7L, null, lastSeen(0)));
    }

    @Test
    void roundTripsAChatCommandWithoutSignatures() {
        assertRoundTrip(new ChatCommandPacket("gamemode creative"));
    }

    @Test
    void roundTripsASignedChatCommandWithArgumentSignatures() {
        assertRoundTrip(new ChatCommandSignedPacket("tell Steve hi", TIMESTAMP, 99L, List.of(
                new ChatCommandSignedPacket.ArgumentSignature("message", signature(0x11)),
                new ChatCommandSignedPacket.ArgumentSignature("target", signature(0x22))),
                lastSeen(2, 1)));
    }

    @Test
    void roundTripsASignedChatCommandWithoutArguments() {
        assertRoundTrip(new ChatCommandSignedPacket("list", TIMESTAMP, 0L, List.of(), lastSeen(0)));
    }

    @Test
    void writesAcknowledgedBitsLeastSignificantBitFirst() {
        var output = encode(new ChatPacket("", Instant.EPOCH, 0L, null, lastSeen(0, 9)));

        try {
            assertEquals(CHAT_ID, readVarInt(output));
            assertEquals(0, readVarInt(output));
            assertEquals(0L, output.readLong());
            assertEquals(0L, output.readLong());
            assertFalse(output.readBoolean());
            assertEquals(0, readVarInt(output));
            assertEquals(0, output.readUnsignedByte());
            assertEquals(2, output.readUnsignedByte());
            assertEquals(0, output.readUnsignedByte());
            assertEquals(LAST_SEEN_CHECKSUM, output.readUnsignedByte());
            assertEquals(0, output.readableBytes());
        } finally {
            output.release();
        }
    }

    @Test
    void rejectsAcknowledgedBitsBeyondTheWindow() {
        var packet = new ChatPacket("", TIMESTAMP, 0L, null, lastSeen(0, LAST_SEEN_WINDOW));
        var output = Unpooled.buffer();

        try {
            assertThrows(EncoderException.class, () -> REGISTRY.encode(STATE, FLOW, output, packet));
        } finally {
            output.release();
        }
    }

    @Test
    void rejectsChatMessagesOverTheCharacterLimit() {
        var packet = new ChatPacket("x".repeat(MAX_CHAT_MESSAGE_CHARACTERS + 1), TIMESTAMP, 0L, null, lastSeen(0));
        var output = Unpooled.buffer();

        try {
            assertThrows(EncoderException.class, () -> REGISTRY.encode(STATE, FLOW, output, packet));
        } finally {
            output.release();
        }
    }

    @Test
    void rejectsMoreArgumentSignaturesThanTheProtocolAllows() {
        var input = Unpooled.buffer();

        try {
            writeVarInt(input, CHAT_COMMAND_SIGNED_ID);
            writeString(input, "list");
            input.writeLong(TIMESTAMP.toEpochMilli());
            input.writeLong(0L);
            writeVarInt(input, MAX_ARGUMENT_SIGNATURES + 1);
            assertThrows(DecoderException.class, () -> REGISTRY.decode(STATE, FLOW, input));
        } finally {
            input.release();
        }
    }

    private static void assertRoundTrip(IPacket packet) {
        var output = encode(packet);

        try {
            assertEquals(packet, REGISTRY.decode(STATE, FLOW, output));
            assertEquals(0, output.readableBytes());
        } finally {
            output.release();
        }
    }

    private static ByteBuf encode(IPacket packet) {
        var output = Unpooled.buffer();
        REGISTRY.encode(STATE, FLOW, output, packet);
        return output;
    }

    private static int encodedId(IPacket packet) {
        var output = encode(packet);

        try {
            return readVarInt(output);
        } finally {
            output.release();
        }
    }

    private static MessageSignature signature(int value) {
        var bytes = new byte[MessageSignature.BYTES];
        Arrays.fill(bytes, (byte) value);
        return new MessageSignature(bytes);
    }

    private static LastSeenUpdate lastSeen(int offset, int... acknowledged) {
        var bits = new BitSet();

        for (var index : acknowledged) {
            bits.set(index);
        }

        return new LastSeenUpdate(offset, bits, (byte) LAST_SEEN_CHECKSUM);
    }
}
