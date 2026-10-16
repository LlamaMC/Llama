package com.llamamc.llama.network.minecraft.protocol;

import com.llamamc.llama.command.CommandRegistry;
import com.llamamc.llama.player.TestPlayers;
import com.llamamc.llamaapi.command.Command;
import com.llamamc.llamaapi.command.ICommandContext;
import com.llamamc.llamaapi.network.minecraft.packet.play.CommandsPacket;
import com.llamamc.llamaapi.network.protocol.DefaultProtocolState;
import com.llamamc.llamaapi.network.protocol.IPacket;
import com.llamamc.llamaapi.network.protocol.IProtocolState;
import com.llamamc.llamaapi.network.protocol.PacketFlow;
import com.llamamc.llamaapi.network.protocol.PacketRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static com.llamamc.llama.network.minecraft.protocol.ProtocolTestBuffers.readVarInt;
import static com.llamamc.llama.network.minecraft.protocol.ProtocolTestBuffers.writeVarInt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(10)
class Minecraft26_3CommandsProtocolTest {
    // Source for the ID below: reports/reports/packets.json of the official 26.3 server, play clientbound commands.
    private static final int COMMANDS_ID = 16;
    private static final IProtocolState STATE = DefaultProtocolState.PLAY;
    private static final PacketFlow FLOW = PacketFlow.CLIENTBOUND;
    private static final PacketRegistry REGISTRY = Minecraft26_3Protocol.createRegistry();

    @Test
    void registersTheCommandsIdFromTheOfficialReport() {
        assertEquals(COMMANDS_ID, encodedId(new CommandsPacket(List.of())));
    }

    @Test
    void roundTripsAnEmptyCommandTreeAsOnlyTheRoot() {
        assertRoundTrip(new CommandsPacket(List.of()));
    }

    @Test
    void roundTripsLiteralNamesInTheirOrder() {
        assertRoundTrip(new CommandsPacket(List.of("help", "gamemode", "stop")));
    }

    // Packet ID 16, then 2 nodes. Node 0 is the root (flags 0) with one child, node 1. Node 1 is the literal "stop"
    // (flags 5: literal type and executable), with no children. The root index is 0.
    @Test
    void writesOneRootAndOneExecutableLiteralPerName() {
        var output = encode(new CommandsPacket(List.of("stop")));

        try {
            assertEquals("100200010105000473746f7000", ByteBufUtil.hexDump(output));
        } finally {
            output.release();
        }
    }

    // The registry lists the name of each command once. The aliases "hi" and "halt" must not reach the packet.
    @Test
    void listsTheRegisteredNamesAndNoAliases() {
        var commands = new CommandRegistry(new TestPlayers.Permissions());
        var handler = new Handler();
        commands.register(handler, handler);

        assertEquals(List.of("greet", "stop"), commands.commandNames());
        assertEquals(new CommandsPacket(List.of("greet", "stop")),
                roundTrip(new CommandsPacket(commands.commandNames())));
    }

    @Test
    void rejectsArgumentNodesThatTheServerDoesNotSend() {
        var input = Unpooled.buffer();

        try {
            writeVarInt(input, COMMANDS_ID);
            writeVarInt(input, 2);
            input.writeByte(0);
            writeVarInt(input, 1);
            writeVarInt(input, 1);
            input.writeByte(2);
            writeVarInt(input, 0);
            writeVarInt(input, 0);
            assertThrows(DecoderException.class, () -> REGISTRY.decode(STATE, FLOW, input));
        } finally {
            input.release();
        }
    }

    @Test
    void rejectsARootThatIsNotTheFirstNode() {
        var input = Unpooled.buffer();

        try {
            writeVarInt(input, COMMANDS_ID);
            writeVarInt(input, 1);
            input.writeByte(0);
            writeVarInt(input, 0);
            writeVarInt(input, 1);
            assertThrows(DecoderException.class, () -> REGISTRY.decode(STATE, FLOW, input));
        } finally {
            input.release();
        }
    }

    private static ByteBuf encode(IPacket packet) {
        var output = Unpooled.buffer();
        REGISTRY.encode(STATE, FLOW, output, packet);
        return output;
    }

    private static IPacket roundTrip(IPacket packet) {
        var output = encode(packet);

        try {
            var decoded = REGISTRY.decode(STATE, FLOW, output);
            assertEquals(0, output.readableBytes());
            return decoded;
        } finally {
            output.release();
        }
    }

    private static void assertRoundTrip(IPacket packet) {
        assertEquals(packet, roundTrip(packet));
    }

    private static int encodedId(IPacket packet) {
        var output = encode(packet);

        try {
            return readVarInt(output);
        } finally {
            output.release();
        }
    }

    private static final class Handler {
        @Command(name = "greet", aliases = {"hi"})
        public void greet(ICommandContext context) {
        }

        @Command(name = "stop", aliases = {"halt"})
        public void stop(ICommandContext context) {
        }
    }
}
