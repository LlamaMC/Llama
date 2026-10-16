package com.llamamc.llama.network.minecraft.protocol;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.network.minecraft.packet.common.DisconnectPacket;
import com.llamamc.llamaapi.network.minecraft.packet.login.LoginDisconnectPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.SystemChatPacket;
import com.llamamc.llamaapi.network.protocol.DefaultProtocolState;
import com.llamamc.llamaapi.network.protocol.IPacket;
import com.llamamc.llamaapi.network.protocol.IProtocolState;
import com.llamamc.llamaapi.network.protocol.PacketFlow;
import com.llamamc.llamaapi.network.protocol.PacketRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;

import static com.llamamc.llama.network.minecraft.protocol.ProtocolTestBuffers.readString;
import static com.llamamc.llama.network.minecraft.protocol.ProtocolTestBuffers.readVarInt;
import static com.llamamc.llama.network.minecraft.protocol.ProtocolTestBuffers.writeString;
import static com.llamamc.llama.network.minecraft.protocol.ProtocolTestBuffers.writeVarInt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class Minecraft26_3DisconnectProtocolTest {
    // Source for every ID below: reports/reports/packets.json of the official 26.3 server.
    private static final int LOGIN_DISCONNECT_ID = 0;
    private static final int CONFIGURATION_DISCONNECT_ID = 2;
    private static final int PLAY_DISCONNECT_ID = 32;
    private static final int SYSTEM_CHAT_ID = 124;
    private static final int NBT_TAG_STRING = 8;
    private static final int NBT_TAG_COMPOUND = 10;
    private static final PacketRegistry REGISTRY = Minecraft26_3Protocol.createRegistry();

    @Test
    void registersTheDisconnectAndSystemChatIdsFromTheOfficialReport() {
        assertEquals(LOGIN_DISCONNECT_ID,
                encodedId(DefaultProtocolState.LOGIN, new LoginDisconnectPacket(Component.of("bye"))));
        assertEquals(CONFIGURATION_DISCONNECT_ID,
                encodedId(DefaultProtocolState.CONFIGURATION, new DisconnectPacket(Component.of("bye"))));
        assertEquals(PLAY_DISCONNECT_ID,
                encodedId(DefaultProtocolState.PLAY, new DisconnectPacket(Component.of("bye"))));
        assertEquals(SYSTEM_CHAT_ID,
                encodedId(DefaultProtocolState.PLAY, new SystemChatPacket(Component.of("hi"), false)));
    }

    @Test
    void roundTripsTheLoginDisconnect() {
        assertRoundTrip(DefaultProtocolState.LOGIN, new LoginDisconnectPacket(Component.of("Server is full")));
    }

    @Test
    void roundTripsTheConfigurationDisconnect() {
        assertRoundTrip(DefaultProtocolState.CONFIGURATION,
                new DisconnectPacket(Component.of("Resource pack refused")));
    }

    @Test
    void roundTripsThePlayDisconnectWithNonAsciiText() {
        assertRoundTrip(DefaultProtocolState.PLAY, new DisconnectPacket(Component.of("Kicked: Grüße")));
    }

    @Test
    void roundTripsSystemChatWithAndWithoutTheOverlayFlag() {
        assertRoundTrip(DefaultProtocolState.PLAY, new SystemChatPacket(Component.of("Server restarting"), true));
        assertRoundTrip(DefaultProtocolState.PLAY, new SystemChatPacket(Component.of("Hello"), false));
    }

    @Test
    void writesPlainTextAsOneNbtStringTag() {
        var output = encode(DefaultProtocolState.PLAY, new SystemChatPacket(Component.of("Hi"), true));

        try {
            assertEquals(SYSTEM_CHAT_ID, readVarInt(output));
            assertEquals(NBT_TAG_STRING, output.readUnsignedByte());
            assertEquals(2, output.readUnsignedShort());
            assertEquals("Hi", output.readCharSequence(2, StandardCharsets.UTF_8).toString());
            assertTrue(output.readBoolean());
            assertEquals(0, output.readableBytes());
        } finally {
            output.release();
        }
    }

    @Test
    void writesTheLoginReasonAsAJsonStringWithoutHtmlEscaping() {
        var output = encode(DefaultProtocolState.LOGIN,
                new LoginDisconnectPacket(Component.of("<a> \"quoted\"")));

        try {
            assertEquals(LOGIN_DISCONNECT_ID, readVarInt(output));
            assertEquals("\"<a> \\\"quoted\\\"\"", readString(output));
            assertEquals(0, output.readableBytes());
        } finally {
            output.release();
        }
    }

    @Test
    void rejectsCompoundTextComponentsFromTheWire() {
        var input = Unpooled.buffer();

        try {
            writeVarInt(input, SYSTEM_CHAT_ID);
            input.writeByte(NBT_TAG_COMPOUND);
            assertThrows(DecoderException.class,
                    () -> REGISTRY.decode(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, input));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsLoginReasonsThatAreJsonObjects() {
        var input = Unpooled.buffer();

        try {
            writeVarInt(input, LOGIN_DISCONNECT_ID);
            writeString(input, "{\"text\":\"Bye\"}");
            assertThrows(DecoderException.class,
                    () -> REGISTRY.decode(DefaultProtocolState.LOGIN, PacketFlow.CLIENTBOUND, input));
        } finally {
            input.release();
        }
    }

    private static ByteBuf encode(IProtocolState state, IPacket packet) {
        var output = Unpooled.buffer();
        REGISTRY.encode(state, PacketFlow.CLIENTBOUND, output, packet);
        return output;
    }

    private static void assertRoundTrip(IProtocolState state, IPacket packet) {
        var output = encode(state, packet);

        try {
            assertEquals(packet, REGISTRY.decode(state, PacketFlow.CLIENTBOUND, output));
            assertEquals(0, output.readableBytes());
        } finally {
            output.release();
        }
    }

    private static int encodedId(IProtocolState state, IPacket packet) {
        var output = encode(state, packet);

        try {
            return readVarInt(output);
        } finally {
            output.release();
        }
    }

}
