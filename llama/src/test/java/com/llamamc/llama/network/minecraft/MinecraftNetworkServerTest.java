package com.llamamc.llama.network.minecraft;

import com.google.gson.JsonParser;
import com.llamamc.llama.network.minecraft.protocol.Minecraft26_3Registries;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
            var status = readPacket(input);
            var offset = new int[]{0};
            assertEquals(0, readVarInt(status, offset));

            var response = JsonParser.parseString(readString(status, offset)).getAsJsonObject();
            assertEquals(ServerStatus.PROTOCOL_VERSION, response.getAsJsonObject("version").get("protocol").getAsInt());
            assertEquals(ServerStatus.VERSION_NAME, response.getAsJsonObject("version").get("name").getAsString());
            assertEquals("Llama test server", response.getAsJsonObject("description").get("text").getAsString());
            assertEquals(42, response.getAsJsonObject("players").get("max").getAsInt());

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
        writePacket(output, packet(packetOutput -> {
            writeVarInt(packetOutput, 0);
            writeVarInt(packetOutput, ServerStatus.PROTOCOL_VERSION);
            writeString(packetOutput, "localhost");
            packetOutput.writeShort(port);
            writeVarInt(packetOutput, 2);
        }));
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

    private static long readLong(byte[] input, int[] offset) {
        var value = 0L;

        for (var index = 0; index < Long.BYTES; index++) {
            value = value << 8 | input[offset[0]++] & 0xFFL;
        }

        return value;
    }

    @FunctionalInterface
    private interface PacketWriter {
        void write(DataOutputStream output) throws IOException;
    }

}
