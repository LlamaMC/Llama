package com.llamamc.llama.world;

import com.llamamc.llama.network.minecraft.protocol.MinecraftProtocolVersion;
import com.llamamc.llama.registry.MinecraftRegistries;
import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.storage.ChunkPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkNbtCodecTest {
    private static final int GENERATOR_VERSION = 7;
    private static final int LEGACY_DATA_VERSION = 5023;
    private static final int MIN_Y = 32;
    private static final int HEIGHT = 16;
    private static final int SNAPSHOT_MAGIC = 0x4C4C4348;
    private static final int SNAPSHOT_VERSION = 1;
    private static final int WIDE_STATE_COUNT = 300;
    private static final int BYTE_STATE_COUNT = 200;

    @Test
    void roundTripsThreeHundredDistinctStatesWithTwoByteIndexes() throws Exception {
        var states = new MinecraftRegistries(MinecraftProtocolVersion.V26_3).blockStates().stream().limit(300).toList();
        var chunk = new CyclingChunk(states);
        var codec = new ChunkNbtCodec();

        var payload = codec.encode(chunk, GENERATOR_VERSION);

        assertEquals(5025, dataVersion(payload));
        var decoded = codec.decode(payload, GENERATOR_VERSION);
        assertBlocksMatch(chunk, decoded);
        assertEquals(300, distinctStates(decoded));
    }

    @Test
    @Timeout(10)
    void demotesToOneByteIndexesOnceBlocksUseAtMost256States() throws Exception {
        var states = syntheticStates(WIDE_STATE_COUNT);
        var blocks = ChunkBlocks.copyOf(new CyclingChunk(states));
        var codec = new ChunkNbtCodec();
        assertEquals(5025, dataVersion(codec.encode(blocks.snapshot(), GENERATOR_VERSION)));

        // Blocks that use states 200 to 299 take states 0 to 99 instead, so only states 0 to 199 remain in use.
        for (var blockY = MIN_Y; blockY < MIN_Y + HEIGHT; blockY++) {
            for (var localZ = 0; localZ < IChunk.WIDTH; localZ++) {
                for (var localX = 0; localX < IChunk.WIDTH; localX++) {
                    var stateIndex = blockIndex(localX, blockY, localZ) % WIDE_STATE_COUNT;
                    if (stateIndex >= BYTE_STATE_COUNT) {
                        blocks.set(localX, blockY, localZ, states.get(stateIndex - BYTE_STATE_COUNT));
                    }
                }
            }
        }

        var snapshot = blocks.snapshot();
        var payload = codec.encode(snapshot, GENERATOR_VERSION);

        assertEquals(5024, dataVersion(payload));
        var decoded = codec.decode(payload, GENERATOR_VERSION);
        assertBlocksMatch(snapshot, decoded);
        assertEquals(BYTE_STATE_COUNT, distinctStates(decoded));
    }

    @Test
    @Timeout(10)
    void keepsTwoByteIndexesWhileMoreThan256StatesStillInUse() throws Exception {
        var states = syntheticStates(WIDE_STATE_COUNT);
        var blocks = ChunkBlocks.copyOf(new CyclingChunk(states));
        // Block index 299 holds state 299. Thirteen other blocks still use that state, so all 300 states remain.
        blocks.set(11, MIN_Y + 1, 2, states.get(0));
        var snapshot = blocks.snapshot();
        var codec = new ChunkNbtCodec();

        var payload = codec.encode(snapshot, GENERATOR_VERSION);

        assertEquals(5025, dataVersion(payload));
        var decoded = codec.decode(payload, GENERATOR_VERSION);
        assertBlocksMatch(snapshot, decoded);
        assertEquals(WIDE_STATE_COUNT, distinctStates(decoded));
    }

    @Test
    void roundTripsBlockPropertiesWithOneByteIndexes() throws Exception {
        var states = List.of(
                new BlockState("minecraft:oak_slab", Map.of("type", "top", "waterlogged", "false")),
                new BlockState("minecraft:lever", Map.of("face", "floor", "facing", "north", "powered", "true")),
                BlockState.AIR,
                new BlockState("minecraft:stone"));
        var chunk = new CyclingChunk(states);
        var codec = new ChunkNbtCodec();

        var payload = codec.encode(chunk, GENERATOR_VERSION);

        assertEquals(5024, dataVersion(payload));
        assertBlocksMatch(chunk, codec.decode(payload, GENERATOR_VERSION));
    }

    @Test
    void readsTheLegacyDataVersionWithKeyAndPropertyEntries() throws Exception {
        var slab = new BlockState("minecraft:oak_slab", Map.of("type", "top", "waterlogged", "false"));
        var stone = new BlockState("minecraft:stone");
        var codec = new ChunkNbtCodec();

        var decoded = codec.decode(legacyPayload(LEGACY_DATA_VERSION, List.of(slab, stone)), GENERATOR_VERSION);

        for (var blockY = MIN_Y; blockY < MIN_Y + HEIGHT; blockY++) {
            for (var localZ = 0; localZ < IChunk.WIDTH; localZ++) {
                for (var localX = 0; localX < IChunk.WIDTH; localX++) {
                    var index = (blockY - MIN_Y) * IChunk.WIDTH * IChunk.WIDTH + localZ * IChunk.WIDTH + localX;
                    assertEquals(index % 2 == 0 ? slab : stone, decoded.block(localX, blockY, localZ));
                }
            }
        }
    }

    @Test
    void rejectsAnUnknownDataVersion() {
        var codec = new ChunkNbtCodec();
        var payload = legacyPayload(9_999, List.of(BlockState.AIR));

        assertThrows(IOException.class, () -> codec.decode(payload, GENERATOR_VERSION));
    }

    private static List<BlockState> syntheticStates(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> new BlockState("minecraft:test_block", Map.of("index", Integer.toString(index))))
                .toList();
    }

    // The order CyclingChunk uses, so a block's state is its index modulo the number of states.
    private static int blockIndex(int localX, int blockY, int localZ) {
        return ((blockY - MIN_Y) * IChunk.WIDTH + localZ) * IChunk.WIDTH + localX;
    }

    private static int dataVersion(ChunkPayload payload) throws IOException {
        var input = new DataInputStream(new ByteArrayInputStream(payload.nbt()));
        input.readUnsignedByte();
        input.readUTF();
        input.readUnsignedByte();
        assertEquals("DataVersion", input.readUTF());
        return input.readInt();
    }

    private static int distinctStates(IChunk chunk) {
        var states = new HashSet<BlockState>();
        for (var blockY = chunk.minY(); blockY < chunk.minY() + chunk.height(); blockY++) {
            for (var localZ = 0; localZ < IChunk.WIDTH; localZ++) {
                for (var localX = 0; localX < IChunk.WIDTH; localX++) {
                    states.add(chunk.block(localX, blockY, localZ));
                }
            }
        }
        return states.size();
    }

    private static void assertBlocksMatch(IChunk expected, IChunk actual) {
        for (var blockY = expected.minY(); blockY < expected.minY() + expected.height(); blockY++) {
            for (var localZ = 0; localZ < IChunk.WIDTH; localZ++) {
                for (var localX = 0; localX < IChunk.WIDTH; localX++) {
                    assertEquals(expected.block(localX, blockY, localZ), actual.block(localX, blockY, localZ),
                            "Block at " + localX + ", " + blockY + ", " + localZ);
                }
            }
        }
    }

    /**
     * Writes the chunk NBT as the first Llama release did. Only the reader still supports this layout.
     */
    private static ChunkPayload legacyPayload(int dataVersion, List<BlockState> palette) {
        try {
            var snapshot = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(snapshot)) {
                output.writeInt(SNAPSHOT_MAGIC);
                output.writeInt(SNAPSHOT_VERSION);
                output.writeInt(0);
                output.writeInt(0);
                output.writeInt(MIN_Y);
                output.writeInt(HEIGHT);
                output.writeInt(palette.size());
                for (var state : palette) {
                    output.writeUTF(state.identifier());
                    output.writeInt(state.properties().size());
                    for (var property : state.properties().entrySet()) {
                        output.writeUTF(property.getKey());
                        output.writeUTF(property.getValue());
                    }
                }
                for (var index = 0; index < IChunk.WIDTH * IChunk.WIDTH * HEIGHT; index++) {
                    output.writeByte(index % palette.size());
                }
            }

            var nbt = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(nbt)) {
                output.writeByte(10);
                output.writeUTF("");
                output.writeByte(3);
                output.writeUTF("DataVersion");
                output.writeInt(dataVersion);
                output.writeByte(3);
                output.writeUTF("llama:generator_version");
                output.writeInt(GENERATOR_VERSION);
                output.writeByte(7);
                output.writeUTF("llama:prepared_chunk");
                output.writeInt(snapshot.size());
                output.write(snapshot.toByteArray());
                output.writeByte(0);
            }
            return new ChunkPayload(nbt.toByteArray());
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private record CyclingChunk(List<BlockState> states) implements IChunk {
        @Override
        public ChunkPosition position() {
            return new ChunkPosition(3, -4);
        }

        @Override
        public int minY() {
            return MIN_Y;
        }

        @Override
        public int height() {
            return HEIGHT;
        }

        @Override
        public BlockState block(int localX, int blockY, int localZ) {
            var index = ((blockY - MIN_Y) * IChunk.WIDTH + localZ) * IChunk.WIDTH + localX;
            return states.get(index % states.size());
        }

        @Override
        public int highestBlockY(int localX, int localZ) {
            return MIN_Y + HEIGHT - 1;
        }
    }
}
