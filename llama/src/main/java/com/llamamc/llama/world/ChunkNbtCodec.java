package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.storage.ChunkPayload;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

/**
 * Chunk NBT with a prepared block snapshot. The data version says how palette entries and indexes are laid out:
 * <ul>
 *     <li>{@code 5023}: key and properties stored separately, one-byte indexes. Read only.</li>
 *     <li>{@code 5024}: state string per palette entry, one-byte indexes, at most 256 states.</li>
 *     <li>{@code 5025}: state string per palette entry, unsigned two-byte indexes, more than 256 states.</li>
 * </ul>
 */
final class ChunkNbtCodec {
    private static final int LEGACY_DATA_VERSION = 5023;
    private static final int DATA_VERSION = 5024;
    private static final int WIDE_INDEX_DATA_VERSION = 5025;
    private static final int MAX_BYTE_PALETTE = 256;
    private static final int MAX_WIDE_PALETTE = 65_536;
    private static final int SECTION_HEIGHT = 16;
    private static final int SNAPSHOT_MAGIC = 0x4C4C4348;
    private static final int SNAPSHOT_VERSION = 1;
    private static final int TAG_END = 0;
    private static final int TAG_INT = 3;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_COMPOUND = 10;
    private static final String DATA_VERSION_TAG = "DataVersion";
    private static final String GENERATOR_VERSION_TAG = "llama:generator_version";
    private static final String SNAPSHOT_TAG = "llama:prepared_chunk";

    ChunkPayload encode(IChunk chunk, int generatorVersion) {
        try {
            var snapshot = encodeSnapshot(chunk);
            var nbtBytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(nbtBytes)) {
                output.writeByte(TAG_COMPOUND);
                output.writeUTF("");
                output.writeByte(TAG_INT);
                output.writeUTF(DATA_VERSION_TAG);
                output.writeInt(snapshot.dataVersion());
                output.writeByte(TAG_INT);
                output.writeUTF(GENERATOR_VERSION_TAG);
                output.writeInt(generatorVersion);
                output.writeByte(TAG_BYTE_ARRAY);
                output.writeUTF(SNAPSHOT_TAG);
                output.writeInt(snapshot.bytes().length);
                output.write(snapshot.bytes());
                output.writeByte(TAG_END);
            }
            return new ChunkPayload(nbtBytes.toByteArray());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    GeneratedChunk decode(ChunkPayload payload, int expectedGeneratorVersion) throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(payload.nbt()))) {
            if (input.readUnsignedByte() != TAG_COMPOUND) {
                throw new IOException("Chunk payload is not an NBT compound");
            }
            input.readUTF();
            var dataVersion = 0;
            var generatorVersion = 0;
            byte[] snapshot = null;
            while (true) {
                var type = input.readUnsignedByte();
                if (type == TAG_END) {
                    break;
                }

                var name = input.readUTF();
                if (type == TAG_INT) {
                    var value = input.readInt();
                    if (name.equals(DATA_VERSION_TAG)) {
                        dataVersion = value;
                    }
                    if (name.equals(GENERATOR_VERSION_TAG)) {
                        generatorVersion = value;
                    }
                    continue;
                }
                if (type == TAG_BYTE_ARRAY && name.equals(SNAPSHOT_TAG)) {
                    snapshot = input.readNBytes(input.readInt());
                    continue;
                }

                throw new IOException("Unsupported chunk NBT tag '" + name + "' of type " + type);
            }
            if (generatorVersion != expectedGeneratorVersion) {
                throw new StaleChunkException(generatorVersion, expectedGeneratorVersion);
            }
            if (snapshot != null) {
                return decodeSnapshot(snapshot, dataVersion);
            }
        }

        throw new IOException("Chunk NBT does not contain a prepared Llama snapshot");
    }

    private static EncodedSnapshot encodeSnapshot(IChunk chunk) throws IOException {
        var palette = new ArrayList<BlockState>();
        var paletteIndexes = new HashMap<BlockState, Integer>();
        var indexes = new int[IChunk.WIDTH * IChunk.WIDTH * chunk.height()];
        var index = 0;
        for (var blockY = chunk.minY(); blockY < chunk.minY() + chunk.height(); blockY++) {
            for (var localZ = 0; localZ < IChunk.WIDTH; localZ++) {
                for (var localX = 0; localX < IChunk.WIDTH; localX++) {
                    var state = chunk.block(localX, blockY, localZ);
                    indexes[index++] = paletteIndexes.computeIfAbsent(state, ignored -> {
                        palette.add(state);
                        return palette.size() - 1;
                    });
                }
            }
        }
        if (palette.size() > MAX_WIDE_PALETTE) {
            throw new IOException("Prepared chunks support at most " + MAX_WIDE_PALETTE + " distinct block states");
        }

        var dataVersion = palette.size() > MAX_BYTE_PALETTE ? WIDE_INDEX_DATA_VERSION : DATA_VERSION;
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(SNAPSHOT_MAGIC);
            output.writeInt(SNAPSHOT_VERSION);
            output.writeInt(chunk.position().x());
            output.writeInt(chunk.position().z());
            output.writeInt(chunk.minY());
            output.writeInt(chunk.height());
            output.writeInt(palette.size());
            for (var state : palette) {
                output.writeUTF(BlockStateStrings.format(state));
            }
            writeIndexes(output, indexes, dataVersion);
        }
        return new EncodedSnapshot(dataVersion, bytes.toByteArray());
    }

    private static void writeIndexes(DataOutputStream output, int[] indexes, int dataVersion) throws IOException {
        if (dataVersion == WIDE_INDEX_DATA_VERSION) {
            for (var index : indexes) {
                output.writeShort(index);
            }
            return;
        }

        for (var index : indexes) {
            output.writeByte(index);
        }
    }

    private static GeneratedChunk decodeSnapshot(byte[] snapshot, int dataVersion) throws IOException {
        var maximumPalette = maximumPalette(dataVersion);
        try (var input = new DataInputStream(new ByteArrayInputStream(snapshot))) {
            if (input.readInt() != SNAPSHOT_MAGIC || input.readInt() != SNAPSHOT_VERSION) {
                throw new IOException("Unsupported prepared chunk snapshot");
            }
            var position = new ChunkPosition(input.readInt(), input.readInt());
            var minY = input.readInt();
            var height = input.readInt();
            if (height <= 0 || height % SECTION_HEIGHT != 0) {
                throw new IOException("Invalid prepared chunk height " + height);
            }
            var paletteSize = input.readInt();
            if (paletteSize < 1 || paletteSize > maximumPalette) {
                throw new IOException("Invalid prepared chunk palette size " + paletteSize);
            }

            var palette = new ArrayList<BlockState>(paletteSize);
            for (var paletteIndex = 0; paletteIndex < paletteSize; paletteIndex++) {
                palette.add(readState(input, dataVersion));
            }
            var blockCount = IChunk.WIDTH * IChunk.WIDTH * height;
            if (dataVersion == WIDE_INDEX_DATA_VERSION) {
                var indexes = new short[blockCount];
                for (var block = 0; block < blockCount; block++) {
                    indexes[block] = input.readShort();
                }
                return new GeneratedChunk(position, minY, height, palette, indexes);
            }

            var indexes = new byte[blockCount];
            input.readFully(indexes);
            return new GeneratedChunk(position, minY, height, palette, indexes);
        }
    }

    private static BlockState readState(DataInputStream input, int dataVersion) throws IOException {
        if (dataVersion == LEGACY_DATA_VERSION) {
            return readLegacyState(input);
        }

        var text = input.readUTF();
        try {
            return BlockStateStrings.parse(text);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid block state in prepared chunk: " + text, error);
        }
    }

    private static BlockState readLegacyState(DataInputStream input) throws IOException {
        var identifier = input.readUTF();
        var propertyCount = input.readInt();
        var properties = new LinkedHashMap<String, String>();
        for (var propertyIndex = 0; propertyIndex < propertyCount; propertyIndex++) {
            properties.put(input.readUTF(), input.readUTF());
        }

        try {
            return new BlockState(identifier, properties);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid block state in prepared chunk: " + identifier, error);
        }
    }

    private static int maximumPalette(int dataVersion) throws IOException {
        return switch (dataVersion) {
            case LEGACY_DATA_VERSION, DATA_VERSION -> MAX_BYTE_PALETTE;
            case WIDE_INDEX_DATA_VERSION -> MAX_WIDE_PALETTE;
            default -> throw new IOException("Unsupported chunk data version " + dataVersion);
        };
    }

    private record EncodedSnapshot(int dataVersion, byte[] bytes) {
    }
}
