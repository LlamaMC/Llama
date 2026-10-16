package com.llamamc.llama.network.minecraft.protocol;

import com.llamamc.llama.registry.MinecraftRegistries;
import com.llamamc.llama.world.FlatChunkGenerator;
import com.llamamc.llama.world.NormalChunkGenerator;
import com.llamamc.llamaapi.concurrent.LlamaFuture;
import com.llamamc.llamaapi.concurrent.LlamaPromise;
import com.llamamc.llamaapi.network.minecraft.packet.play.ChunkBatchReceivedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ForgetLevelChunkPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.GameEvent;
import com.llamamc.llamaapi.network.minecraft.packet.play.GameEventPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.LevelChunkWithLightPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.MovePlayerPositionPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.MovePlayerPositionRotationPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.MovePlayerRotationPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.MovePlayerStatusPacket;
import com.llamamc.llamaapi.network.protocol.DefaultProtocolState;
import com.llamamc.llamaapi.network.protocol.PacketFlow;
import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.ChunkTicketType;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.IChunkTicket;
import com.llamamc.llamaapi.world.IWorld;
import com.llamamc.llamaapi.world.WorldType;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Minecraft26_3ChunkProtocolTest {
    private static final int FIVE_BIT_PACKED_LONGS = 342;
    private static final int DIRECT_PACKED_LONGS = 1024;
    private static final List<String> SIMPLE_STATES = List.of(
            "minecraft:air", "minecraft:stone", "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
            "minecraft:bedrock", "minecraft:dirt", "minecraft:cobblestone", "minecraft:gravel", "minecraft:sand",
            "minecraft:gold_ore", "minecraft:iron_ore", "minecraft:coal_ore", "minecraft:oak_planks",
            "minecraft:glass", "minecraft:bricks", "minecraft:bookshelf");

    @Test
    void decodesThe26_3MovementAndChunkFlowPackets() {
        var registry = Minecraft26_3Protocol.createRegistry();
        assertEquals(new MovePlayerPositionPacket(17.5, 80.0, -33.25, true, true),
                decode(registry, 0x1E, output -> {
                    output.writeDouble(17.5);
                    output.writeDouble(80.0);
                    output.writeDouble(-33.25);
                    output.writeByte(3);
                }));
        assertEquals(new MovePlayerPositionRotationPacket(1.0, 2.0, 3.0, 90.0F, -20.0F, true, false),
                decode(registry, 0x1F, output -> {
                    output.writeDouble(1.0);
                    output.writeDouble(2.0);
                    output.writeDouble(3.0);
                    output.writeFloat(90.0F);
                    output.writeFloat(-20.0F);
                    output.writeByte(1);
                }));
        assertEquals(new MovePlayerRotationPacket(45.0F, 10.0F, false, true),
                decode(registry, 0x20, output -> {
                    output.writeFloat(45.0F);
                    output.writeFloat(10.0F);
                    output.writeByte(2);
                }));
        assertEquals(new MovePlayerStatusPacket(true, false),
                decode(registry, 0x21, output -> output.writeByte(1)));
        assertEquals(new ChunkBatchReceivedPacket(12.5F),
                decode(registry, 0x0B, output -> output.writeFloat(12.5F)));
    }

    @Test
    void encodesSignedChunkCoordinatesForUnload() {
        var output = Unpooled.buffer();

        try {
            Minecraft26_3Protocol.createRegistry().encode(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND,
                    output, new ForgetLevelChunkPacket(new ChunkPosition(-7, 13)));
            assertEquals(0x26, readVarInt(output));
            assertEquals(Integer.toUnsignedLong(-7) | Integer.toUnsignedLong(13) << Integer.SIZE,
                    output.readLong());
            assertEquals(0, output.readableBytes());
        } finally {
            output.release();
        }
    }

    @Test
    void encodesThe26_3LevelLoadStartEvent() {
        var output = Unpooled.buffer();

        try {
            Minecraft26_3Protocol.createRegistry().encode(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND,
                    output, new GameEventPacket(GameEvent.LEVEL_CHUNKS_LOAD_START));
            assertEquals(0x27, readVarInt(output));
            assertEquals(13, output.readUnsignedByte());
            assertEquals(0.0F, output.readFloat());
            assertEquals(0, output.readableBytes());
        } finally {
            output.release();
        }
    }

    @Test
    void encodesACompleteFlatChunkWithLighting() {
        var world = new StubWorld();
        var chunk = new FlatChunkGenerator().generate(world, new ChunkPosition(-2, 3));
        var registry = Minecraft26_3Protocol.createRegistry();
        var output = Unpooled.buffer();

        try {
            registry.encode(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, output,
                    new LevelChunkWithLightPacket(chunk));
            assertEquals(0x2E, readVarInt(output));
            assertEquals(-2, output.readInt());
            assertEquals(3, output.readInt());
            assertTrue(output.readableBytes() > 50_000);
        } finally {
            output.release();
        }
    }

    @Test
    void seventeenBlockStatesUseAFiveBitPalette() {
        var output = encodeSingleSection(17);

        try {
            var sections = sectionBytes(output);
            sections.skipBytes(Short.BYTES * 2);
            assertEquals(5, sections.readUnsignedByte());
            assertEquals(17, readVarInt(sections));
            for (var state = 0; state < 17; state++) {
                readVarInt(sections);
            }
            sections.skipBytes(FIVE_BIT_PACKED_LONGS * Long.BYTES);
            assertEquals(0, sections.readUnsignedByte());
            assertEquals(41, readVarInt(sections));
            assertEquals(0, sections.readableBytes());
        } finally {
            output.release();
        }
    }

    @Test
    void threeHundredStatesUseDirectGlobalIdsWithoutAPalette() {
        var gameRegistries = new MinecraftRegistries(MinecraftProtocolVersion.V26_3);
        var states = gameRegistries.blockStates().stream().limit(300).toList();
        var output = Unpooled.buffer();

        try {
            Minecraft26_3Protocol.createRegistry(gameRegistries).encode(DefaultProtocolState.PLAY,
                    PacketFlow.CLIENTBOUND, output, new LevelChunkWithLightPacket(new SingleSectionChunk(states)));
            assertEquals(0x2E, readVarInt(output));
            var sections = sectionBytes(output);
            sections.skipBytes(Short.BYTES * 2);
            assertEquals(16, sections.readUnsignedByte());
            var packed = new long[DIRECT_PACKED_LONGS];
            for (var index = 0; index < packed.length; index++) {
                packed[index] = sections.readLong();
            }
            for (var position = 0; position < 4096; position++) {
                var value = (int) (packed[position / 4] >>> (position % 4 * 16) & 0xFFFF);
                assertEquals(gameRegistries.blockStateId(states.get(position % states.size())), value,
                        "Wrong global ID at block " + position);
            }
            assertEquals(0, sections.readUnsignedByte());
            assertEquals(41, readVarInt(sections));
            assertEquals(0, sections.readableBytes());
        } finally {
            output.release();
        }
    }

    @Test
    void normalChunkKeepsChunkAndLightPayloadsAligned() {
        var chunk = new NormalChunkGenerator(0L).generate(null, new ChunkPosition(0, 0));
        var output = Unpooled.buffer();

        try {
            Minecraft26_3Protocol.createRegistry().encode(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND,
                    output, new LevelChunkWithLightPacket(chunk));
            assertEquals(0x2E, readVarInt(output));
            output.skipBytes(Integer.BYTES * 2);
            skipHeightmaps(output);
            var sectionBytes = readVarInt(output);
            var sections = output.readSlice(sectionBytes);
            for (var section = 0; section < 24; section++) {
                skipSection(sections);
            }
            assertEquals(0, sections.readableBytes());
            assertEquals(0, readVarInt(output));
            assertEquals(4, readVarInt(output));
            assertEquals(0xFF, output.readUnsignedByte());
            assertEquals(0xFF, output.readUnsignedByte());
            assertEquals(0xFF, output.readUnsignedByte());
            assertEquals(0x03, output.readUnsignedByte());
            assertEquals(0, readVarInt(output));
            assertEquals(0, readVarInt(output));
            assertEquals(0, readVarInt(output));
            var skyUpdates = readVarInt(output);
            assertEquals(26, skyUpdates);
            for (var update = 0; update < skyUpdates; update++) {
                var bytes = readVarInt(output);
                assertEquals(2048, bytes);
                output.skipBytes(bytes);
            }
            assertEquals(0, readVarInt(output));
            assertEquals(0, output.readableBytes());
        } finally {
            output.release();
        }
    }

    private static ByteBuf encodeSingleSection(int distinctStates) {
        var states = SIMPLE_STATES.stream()
                .limit(distinctStates)
                .map(identifier -> new BlockState(identifier))
                .toList();
        var output = Unpooled.buffer();
        Minecraft26_3Protocol.createRegistry().encode(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND,
                output, new LevelChunkWithLightPacket(new SingleSectionChunk(states)));
        assertEquals(0x2E, readVarInt(output));
        return output;
    }

    private static ByteBuf sectionBytes(ByteBuf output) {
        output.skipBytes(Integer.BYTES * 2);
        skipHeightmaps(output);
        return output.readSlice(readVarInt(output));
    }

    private static void skipHeightmaps(io.netty.buffer.ByteBuf input) {
        var count = readVarInt(input);
        for (var index = 0; index < count; index++) {
            readVarInt(input);
            skipLongArray(input);
        }
    }

    private static void skipSection(io.netty.buffer.ByteBuf input) {
        input.skipBytes(Short.BYTES * 2);
        skipPalette(input, 4096, true);
        skipPalette(input, 64, false);
    }

    private static void skipPalette(io.netty.buffer.ByteBuf input, int entries, boolean blocks) {
        var bits = input.readUnsignedByte();
        if (bits == 0) {
            readVarInt(input);
            return;
        }
        var localMaximum = blocks ? 8 : 3;
        if (bits <= localMaximum) {
            var paletteSize = readVarInt(input);
            for (var index = 0; index < paletteSize; index++) {
                readVarInt(input);
            }
        }
        var valuesPerLong = Long.SIZE / bits;
        var longs = (entries + valuesPerLong - 1) / valuesPerLong;
        input.skipBytes(longs * Long.BYTES);
    }

    private static void skipLongArray(io.netty.buffer.ByteBuf input) {
        input.skipBytes(readVarInt(input) * Long.BYTES);
    }

    private static int readVarInt(io.netty.buffer.ByteBuf input) {
        var result = 0;
        for (var position = 0; position < 5; position++) {
            var value = input.readByte() & 0xFF;
            result |= (value & 0x7F) << position * 7;
            if ((value & 0x80) == 0) {
                return result;
            }
        }
        throw new IllegalStateException("Invalid VarInt");
    }

    private static Object decode(com.llamamc.llamaapi.network.protocol.PacketRegistry registry, int packetId,
                                 Consumer<ByteBuf> payload) {
        var input = Unpooled.buffer();
        try {
            input.writeByte(packetId);
            payload.accept(input);
            return registry.decode(DefaultProtocolState.PLAY, PacketFlow.SERVERBOUND, input);
        } finally {
            input.release();
        }
    }

    private record SingleSectionChunk(List<BlockState> states) implements IChunk {
        @Override
        public ChunkPosition position() {
            return new ChunkPosition(0, 0);
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int height() {
            return IChunk.WIDTH;
        }

        @Override
        public BlockState block(int localX, int blockY, int localZ) {
            var index = (blockY * IChunk.WIDTH + localZ) * IChunk.WIDTH + localX;
            return states.get(index % states.size());
        }

        @Override
        public int highestBlockY(int localX, int localZ) {
            return height() - 1;
        }
    }

    private static final class StubWorld implements IWorld {
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
            throw new UnsupportedOperationException();
        }

        @Override
        public LlamaFuture<IChunkTicket> ticket(int chunkX, int chunkZ, ChunkTicketType type) {
            var promise = new LlamaPromise<IChunkTicket>();
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
}
