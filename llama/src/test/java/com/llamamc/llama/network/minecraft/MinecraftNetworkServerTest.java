package com.llamamc.llama.network.minecraft;

import com.google.gson.JsonParser;
import com.llamamc.llama.command.CommandRegistry;
import com.llamamc.llama.event.EventBus;
import com.llamamc.llama.network.minecraft.protocol.Minecraft26_3Protocol;
import com.llamamc.llama.network.minecraft.protocol.Minecraft26_3Registries;
import com.llamamc.llama.player.PlayerLifecycle;
import com.llamamc.llama.player.TestPlayers;
import com.llamamc.llama.world.FlatChunkGenerator;
import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.concurrent.LlamaFuture;
import com.llamamc.llamaapi.concurrent.LlamaPromise;
import com.llamamc.llamaapi.event.impl.player.PlayerJoinEvent;
import com.llamamc.llamaapi.event.impl.player.PlayerPreJoinEvent;
import com.llamamc.llamaapi.network.minecraft.packet.common.DisconnectPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ChunkBatchFinishedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.GameEvent;
import com.llamamc.llamaapi.network.minecraft.packet.play.GameEventPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.PlayLoginPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.PlayerPositionPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.SetChunkCacheCenterPacket;
import com.llamamc.llamaapi.network.protocol.DefaultProtocolState;
import com.llamamc.llamaapi.network.protocol.IPacket;
import com.llamamc.llamaapi.network.protocol.PacketFlow;
import com.llamamc.llamaapi.network.protocol.PacketRegistry;
import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.ChunkTicketType;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.IChunkTicket;
import com.llamamc.llamaapi.world.IWorld;
import com.llamamc.llamaapi.world.WorldType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftNetworkServerTest {

    @Test
    void respondsToA26_3StatusPing() throws IOException {
        var server = new MinecraftNetworkServer(new ServerStatus("Llama test server", 42));
        server.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

        try (var socket = new Socket()) {
            var address = server.address();
            socket.connect(address);
            socket.setSoTimeout(5_000);

            var output = new DataOutputStream(socket.getOutputStream());
            writeHandshake(output, address.getPort());
            writePacket(output, new byte[]{0});
            output.flush();

            var input = new DataInputStream(socket.getInputStream());
            assertStatusResponse(readPacket(input));

            writePacket(output, pongRequest(9_876_543_210L));
            output.flush();

            var pong = readPacket(input);
            var pongOffset = new int[]{0};
            assertEquals(1, readVarInt(pong, pongOffset));
            assertEquals(9_876_543_210L, readLong(pong, pongOffset));
        } finally {
            server.close();
        }

        assertFalse(server.isRunning());
    }

    @Test
    void completesThe26_3OfflineJoinSequence() throws IOException {
        var server = new MinecraftNetworkServer(new ServerStatus("Llama test server", 42));
        server.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));

        try (var socket = new Socket()) {
            var address = server.address();
            socket.connect(address);
            socket.setSoTimeout(5_000);

            var output = new DataOutputStream(socket.getOutputStream());
            writeLoginHandshake(output, address.getPort());
            writePacket(output, loginStart("LlamaTester", UUID.randomUUID()));
            output.flush();

            var input = new DataInputStream(socket.getInputStream());
            var loginFinished = readPacket(input);
            var loginOffset = new int[]{0};
            assertEquals(2, readVarInt(loginFinished, loginOffset));
            loginOffset[0] += Long.BYTES * 2;
            assertEquals("LlamaTester", readString(loginFinished, loginOffset));
            assertEquals(0, readVarInt(loginFinished, loginOffset));
            loginOffset[0] += Long.BYTES * 2;
            assertEquals(loginFinished.length, loginOffset[0]);

            writePacket(output, new byte[]{3});
            output.flush();

            var enabledFeatures = readPacket(input);
            var featureOffset = new int[]{0};
            assertEquals(13, readVarInt(enabledFeatures, featureOffset));
            assertEquals(1, readVarInt(enabledFeatures, featureOffset));
            assertEquals("minecraft:vanilla", readString(enabledFeatures, featureOffset));

            var knownPacks = readPacket(input);
            var knownPackOffset = new int[]{0};
            assertEquals(15, readVarInt(knownPacks, knownPackOffset));
            var knownPackPayload = copyFrom(knownPacks, knownPackOffset[0]);

            writePacket(output, clientInformation());
            writePacket(output, customBrandPayload());
            writePacket(output, packetWithPayload(7, knownPackPayload));
            output.flush();

            for (var index = 0; index < Minecraft26_3Registries.packets().size(); index++) {
                var registryData = readPacket(input);
                assertEquals(7, readVarInt(registryData));
            }

            var tags = readPacket(input);
            var tagOffset = new int[]{0};
            assertEquals(14, readVarInt(tags, tagOffset));
            assertEquals(15, readVarInt(tags, tagOffset));

            var finishConfiguration = readPacket(input);
            assertEquals(3, readVarInt(finishConfiguration));

            writePacket(output, new byte[]{3});
            output.flush();

            assertEquals(0x32, readVarInt(readPacket(input)));
            assertEquals(0x49, readVarInt(readPacket(input)));
        } finally {
            server.close();
        }
    }

    @Test
    @Timeout(10)
    void answersStatusAndPingThroughTheHandlerWithoutASocket() throws IOException {
        var channel = new EmbeddedChannel(new MinecraftConnectionHandler(
                new ServerStatus("Llama test server", 42), Minecraft26_3Protocol.createRegistry(), null, null));

        channel.writeInbound(Unpooled.wrappedBuffer(handshake(25_565, 1)));
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0}));
        assertStatusResponse(outbound(channel));

        channel.writeInbound(Unpooled.wrappedBuffer(pongRequest(9_876_543_210L)));
        var pong = outbound(channel);
        var pongOffset = expectPacketId(pong, 1);
        assertEquals(9_876_543_210L, readLong(pong, pongOffset));
        assertNull(channel.readOutbound());
        // The server closes the status connection once the pong is flushed.
        assertFalse(channel.isOpen());
    }

    @Test
    @Timeout(10)
    void streamsTheJoinSequenceAndTheFirstChunkBatchThroughTheHandler() throws IOException {
        var events = new EventBus();
        var permissions = new TestPlayers.Permissions();
        var scheduler = new TestPlayers.Scheduler();
        var lifecycle = new PlayerLifecycle(scheduler, events, new CommandRegistry(permissions), permissions);
        var joins = new ArrayList<PlayerJoinEvent>();
        events.on(PlayerJoinEvent.class, joins::add);
        var world = new FlatWorld();
        var registry = Minecraft26_3Protocol.createRegistry();
        // Runnable::run as the chunk executor runs each load on the calling thread. The hop back to the event loop is
        // then queued on this thread, so the test drains it with runPendingTasks and no thread races the queue.
        var channel = new EmbeddedChannel(new MinecraftConnectionHandler(
                new ServerStatus("Llama test server", 10), registry, world, lifecycle, Runnable::run));

        logInThroughConfiguration(channel, "LlamaTester");
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{3}));
        scheduler.runQueued();

        var playLogin = outbound(channel);
        expectPacketId(playLogin, 0x32);
        var login = assertInstanceOf(PlayLoginPacket.class,
                decodeOutbound(registry, DefaultProtocolState.PLAY, playLogin));
        assertTrue(login.playerId() > 0);
        assertEquals(10, login.maxPlayers());

        var gameEvent = outbound(channel);
        expectPacketId(gameEvent, 0x27);
        assertEquals(new GameEventPacket(GameEvent.LEVEL_CHUNKS_LOAD_START),
                decodeOutbound(registry, DefaultProtocolState.PLAY, gameEvent));

        var chunkCenter = outbound(channel);
        expectPacketId(chunkCenter, 0x60);
        assertEquals(new SetChunkCacheCenterPacket(0, 0), decodeOutbound(registry, DefaultProtocolState.PLAY, chunkCenter));

        channel.runPendingTasks();

        expectPacketId(outbound(channel), 0x0C);
        var sentChunks = new HashSet<ChunkPosition>();
        for (var index = 0; index < 9; index++) {
            var chunk = outbound(channel);
            var offset = expectPacketId(chunk, 0x2E);
            var chunkX = readInt(chunk, offset);
            var chunkZ = readInt(chunk, offset);
            sentChunks.add(new ChunkPosition(chunkX, chunkZ));
        }
        // The first batch is the center chunk and the eight chunks around it, nearest first.
        assertEquals(Set.of(
                new ChunkPosition(0, 0),
                new ChunkPosition(1, 0), new ChunkPosition(-1, 0), new ChunkPosition(0, 1), new ChunkPosition(0, -1),
                new ChunkPosition(1, 1), new ChunkPosition(1, -1), new ChunkPosition(-1, 1), new ChunkPosition(-1, -1)),
                sentChunks);

        var batchFinished = outbound(channel);
        expectPacketId(batchFinished, 0x0B);
        assertEquals(new ChunkBatchFinishedPacket(9), decodeOutbound(registry, DefaultProtocolState.PLAY, batchFinished));

        var spawnY = new FlatChunkGenerator().generate(world, new ChunkPosition(0, 0)).highestBlockY(0, 0) + 1.0;
        var spawn = outbound(channel);
        expectPacketId(spawn, 0x49);
        assertEquals(new PlayerPositionPacket(0, 0.5, spawnY, 0.5, 0.0F, 0.0F),
                decodeOutbound(registry, DefaultProtocolState.PLAY, spawn));
        assertNull(channel.readOutbound());

        assertEquals(1, joins.size());
        assertEquals(1, lifecycle.registry().online().size());
        channel.close();
    }

    @Test
    @Timeout(10)
    void aCanceledPreJoinEventSendsTheReasonAndRegistersNothing() throws IOException {
        var events = new EventBus();
        var permissions = new TestPlayers.Permissions();
        var scheduler = new TestPlayers.Scheduler();
        var lifecycle = new PlayerLifecycle(scheduler, events, new CommandRegistry(permissions), permissions);
        var joins = new ArrayList<PlayerJoinEvent>();
        events.on(PlayerJoinEvent.class, joins::add);
        events.on(PlayerPreJoinEvent.class, event -> {
            event.cancel(true);
            event.setReason(Component.of("Maintenance"));
        });
        var registry = Minecraft26_3Protocol.createRegistry();
        var channel = new EmbeddedChannel(new MinecraftConnectionHandler(
                new ServerStatus("Llama test server", 10), registry, null, lifecycle));

        logInThroughConfiguration(channel, "LlamaTester");
        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{3}));
        scheduler.runQueued();

        // The pre-join event runs after the configuration acknowledgement, so the reason travels as a configuration
        // disconnect (id 2). Nothing from the play state is sent, and the socket closes after the disconnect.
        var disconnect = outbound(channel);
        expectPacketId(disconnect, 2);
        assertEquals(new DisconnectPacket(Component.of("Maintenance")),
                decodeOutbound(registry, DefaultProtocolState.CONFIGURATION, disconnect));
        assertNull(channel.readOutbound());
        assertFalse(channel.isOpen());
        assertTrue(joins.isEmpty());
        assertTrue(lifecycle.registry().online().isEmpty());
    }

    // Runs the client side of the login and configuration states, and checks the server's packets on the way. It stops
    // before the client's configuration acknowledgement.
    private static void logInThroughConfiguration(EmbeddedChannel channel, String username) throws IOException {
        channel.writeInbound(Unpooled.wrappedBuffer(handshake(25_565, 2)));
        channel.writeInbound(Unpooled.wrappedBuffer(loginStart(username, UUID.randomUUID())));

        var loginFinished = outbound(channel);
        var offset = expectPacketId(loginFinished, 2);
        var profileMostBits = readLong(loginFinished, offset);
        var profileLeastBits = readLong(loginFinished, offset);
        assertEquals(UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)),
                new UUID(profileMostBits, profileLeastBits));
        assertEquals(username, readString(loginFinished, offset));

        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{3}));
        expectPacketId(outbound(channel), 13);
        expectPacketId(outbound(channel), 15);

        channel.writeInbound(Unpooled.wrappedBuffer(clientInformation()));
        channel.writeInbound(Unpooled.wrappedBuffer(customBrandPayload()));
        channel.writeInbound(Unpooled.wrappedBuffer(knownPacksSelected()));
        for (var index = 0; index < Minecraft26_3Registries.packets().size(); index++) {
            expectPacketId(outbound(channel), 7);
        }
        expectPacketId(outbound(channel), 14);
        expectPacketId(outbound(channel), 3);
    }

    private static byte[] handshake(int port, int nextState) throws IOException {
        return packet(output -> {
            writeVarInt(output, 0);
            writeVarInt(output, ServerStatus.PROTOCOL_VERSION);
            writeString(output, "localhost");
            output.writeShort(port);
            writeVarInt(output, nextState);
        });
    }

    private static byte[] knownPacksSelected() throws IOException {
        return packet(output -> {
            writeVarInt(output, 7);
            writeVarInt(output, 1);
            writeString(output, "minecraft");
            writeString(output, "core");
            writeString(output, "26.3");
        });
    }

    // Takes the next outbound payload, with its packet id still in front, and releases the buffer.
    private static byte[] outbound(EmbeddedChannel channel) {
        ByteBuf message = channel.readOutbound();
        assertNotNull(message);
        try {
            return ByteBufUtil.getBytes(message);
        } finally {
            message.release();
        }
    }

    private static IPacket decodeOutbound(PacketRegistry registry, DefaultProtocolState state, byte[] payload) {
        return registry.decode(state, PacketFlow.CLIENTBOUND, Unpooled.wrappedBuffer(payload));
    }

    // Checks the packet id and returns an offset that points just past it.
    private static int[] expectPacketId(byte[] payload, int expectedId) {
        var offset = new int[]{0};
        assertEquals(expectedId, readVarInt(payload, offset));
        return offset;
    }

    private static void assertStatusResponse(byte[] status) {
        var offset = expectPacketId(status, 0);
        var response = JsonParser.parseString(readString(status, offset)).getAsJsonObject();
        assertEquals(ServerStatus.PROTOCOL_VERSION, response.getAsJsonObject("version").get("protocol").getAsInt());
        assertEquals(ServerStatus.VERSION_NAME, response.getAsJsonObject("version").get("name").getAsString());
        assertEquals("Llama test server", response.getAsJsonObject("description").get("text").getAsString());
        assertEquals(42, response.getAsJsonObject("players").get("max").getAsInt());
    }

    private static void writeHandshake(DataOutputStream output, int port) throws IOException {
        var packet = new byte[1 + 2 + 1 + 9 + 2 + 1];
        var index = 0;
        packet[index++] = 0;
        index = writeVarInt(packet, index, ServerStatus.PROTOCOL_VERSION);
        packet[index++] = 9;
        for (var character : "localhost".getBytes(StandardCharsets.UTF_8)) {
            packet[index++] = character;
        }
        packet[index++] = (byte) (port >>> 8);
        packet[index++] = (byte) port;
        packet[index] = 1;
        writePacket(output, packet);
    }

    private static void writeLoginHandshake(DataOutputStream output, int port) throws IOException {
        writePacket(output, handshake(port, 2));
    }

    private static byte[] loginStart(String username, UUID profileId) throws IOException {
        return packet(output -> {
            writeVarInt(output, 0);
            writeString(output, username);
            output.writeLong(profileId.getMostSignificantBits());
            output.writeLong(profileId.getLeastSignificantBits());
        });
    }

    private static byte[] clientInformation() throws IOException {
        return packet(output -> {
            writeVarInt(output, 0);
            writeString(output, "en_us");
            output.writeByte(10);
            writeVarInt(output, 0);
            output.writeBoolean(true);
            output.writeByte(0x7F);
            writeVarInt(output, 1);
            output.writeBoolean(false);
            output.writeBoolean(true);
            writeVarInt(output, 0);
        });
    }

    private static byte[] customBrandPayload() throws IOException {
        return packet(output -> {
            writeVarInt(output, 2);
            writeString(output, "minecraft:brand");
            writeString(output, "vanilla");
        });
    }

    private static byte[] packetWithPayload(int packetId, byte[] payload) throws IOException {
        return packet(output -> {
            writeVarInt(output, packetId);
            output.write(payload);
        });
    }

    private static byte[] packet(PacketWriter writer) throws IOException {
        var bytes = new ByteArrayOutputStream();

        try (var output = new DataOutputStream(bytes)) {
            writer.write(output);
        }

        return bytes.toByteArray();
    }

    private static byte[] pongRequest(long value) {
        var packet = new byte[1 + Long.BYTES];
        packet[0] = 1;

        for (var index = 0; index < Long.BYTES; index++) {
            packet[index + 1] = (byte) (value >>> (Long.BYTES - 1 - index) * 8);
        }

        return packet;
    }

    private static void writePacket(DataOutputStream output, byte[] packet) throws IOException {
        writeVarInt(output, packet.length);
        output.write(packet);
    }

    private static byte[] readPacket(DataInputStream input) throws IOException {
        var length = readVarInt(input);
        return input.readNBytes(length);
    }

    private static String readString(byte[] input, int[] offset) {
        var length = readVarInt(input, offset);
        var value = new String(input, offset[0], length, StandardCharsets.UTF_8);
        offset[0] += length;
        return value;
    }

    private static byte[] copyFrom(byte[] input, int offset) {
        var result = new byte[input.length - offset];
        System.arraycopy(input, offset, result, 0, result.length);
        return result;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(output, bytes.length);
        output.write(bytes);
    }

    private static int readVarInt(byte[] input) {
        return readVarInt(input, new int[]{0});
    }

    private static int readVarInt(byte[] input, int[] offset) {
        var result = 0;
        var position = 0;

        while (true) {
            var value = input[offset[0]++] & 0xFF;
            result |= (value & 0x7F) << position++ * 7;

            if ((value & 0x80) == 0) {
                return result;
            }
        }
    }

    private static int readVarInt(DataInputStream input) throws IOException {
        var result = 0;
        var position = 0;

        while (true) {
            var value = input.readUnsignedByte();
            result |= (value & 0x7F) << position++ * 7;

            if ((value & 0x80) == 0) {
                return result;
            }
        }
    }

    private static int writeVarInt(byte[] output, int offset, int value) {
        while ((value & ~0x7F) != 0) {
            output[offset++] = (byte) (value & 0x7F | 0x80);
            value >>>= 7;
        }

        output[offset++] = (byte) value;
        return offset;
    }

    private static void writeVarInt(DataOutputStream output, int value) throws IOException {
        while ((value & ~0x7F) != 0) {
            output.writeByte(value & 0x7F | 0x80);
            value >>>= 7;
        }

        output.writeByte(value);
    }

    private static int readInt(byte[] input, int[] offset) {
        var value = 0;

        for (var index = 0; index < Integer.BYTES; index++) {
            value = value << 8 | input[offset[0]++] & 0xFF;
        }

        return value;
    }

    private static long readLong(byte[] input, int[] offset) {
        var value = 0L;

        for (var index = 0; index < Long.BYTES; index++) {
            value = value << 8 | input[offset[0]++] & 0xFFL;
        }

        return value;
    }

    // Serves the flat generator's chunks from memory. The protocol path only calls chunkNow.
    private static final class FlatWorld implements IWorld {
        private final FlatChunkGenerator generator = new FlatChunkGenerator();

        @Override
        public String name() {
            return "test";
        }

        @Override
        public String seed() {
            return "0";
        }

        @Override
        public Optional<IChunk> loadedChunk(int chunkX, int chunkZ) {
            return Optional.empty();
        }

        @Override
        public boolean unloadChunk(int chunkX, int chunkZ) {
            return false;
        }

        @Override
        public LlamaFuture<IChunk> chunk(int chunkX, int chunkZ) {
            var promise = new LlamaPromise<IChunk>();
            promise.fail(new UnsupportedOperationException());
            return promise.future();
        }

        @Override
        public IChunk chunkNow(int chunkX, int chunkZ) {
            return generator.generate(this, new ChunkPosition(chunkX, chunkZ));
        }

        @Override
        public LlamaFuture<IChunkTicket> ticket(int chunkX, int chunkZ, ChunkTicketType type) {
            var promise = new LlamaPromise<IChunkTicket>();
            promise.fail(new UnsupportedOperationException());
            return promise.future();
        }

        @Override
        public void setBlock(int x, int y, int z, BlockState state) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LlamaFuture<Void> setBlockAsync(int x, int y, int z, BlockState state) {
            var promise = new LlamaPromise<Void>();
            promise.fail(new UnsupportedOperationException());
            return promise.future();
        }

        @Override
        public DimensionType dimension() {
            return DimensionType.OVERWORLD;
        }

        @Override
        public WorldType type() {
            return WorldType.FLAT;
        }
    }

    @FunctionalInterface
    private interface PacketWriter {
        void write(DataOutputStream output) throws IOException;
    }

}
