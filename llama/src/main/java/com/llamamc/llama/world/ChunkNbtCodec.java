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
import java.util.LinkedHashMap;
import java.util.List;

final class ChunkNbtCodec {
    private static final int DATA_VERSION = 5023;
    private static final int SNAPSHOT_MAGIC = 0x4C4C4348;
    private static final int SNAPSHOT_VERSION = 1;
    private static final int TAG_END = 0;
    private static final int TAG_INT = 3;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_COMPOUND = 10;
    private static final String GENERATOR_VERSION_TAG = "llama:generator_version";
    private static final String SNAPSHOT_TAG = "llama:prepared_chunk";

    ChunkPayload encode(IChunk chunk, int generatorVersion) {
        try {
            var nbtBytes = new ByteArrayOutputStream();
            try (var output = new DataOutputStream(nbtBytes)) {
                output.writeByte(TAG_COMPOUND);
                output.writeUTF("");
                output.writeByte(TAG_INT);
                output.writeUTF("DataVersion");
                output.writeInt(DATA_VERSION);
                output.writeByte(TAG_INT);
                output.writeUTF(GENERATOR_VERSION_TAG);
                output.writeInt(generatorVersion);
                var snapshot = encodeSnapshot(chunk);
                output.writeByte(TAG_BYTE_ARRAY);
                output.writeUTF(SNAPSHOT_TAG);
                output.writeInt(snapshot.length);
                output.write(snapshot);
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
                return decodeSnapshot(snapshot);
            }
        }

        throw new IOException("Chunk NBT does not contain a prepared Llama snapshot");
    }

    private byte[] encodeSnapshot(IChunk chunk) throws IOException {
        var palette = new ArrayList<BlockState>();
        var paletteIndexes = new LinkedHashMap<BlockState, Integer>();
        var blocks = new byte[IChunk.WIDTH * IChunk.WIDTH * chunk.height()];
        var index = 0;
        for (var blockY = chunk.minY(); blockY < chunk.minY() + chunk.height(); blockY++) {
            for (var localZ = 0; localZ < IChunk.WIDTH; localZ++) {
                for (var localX = 0; localX < IChunk.WIDTH; localX++) {
                    var state = chunk.block(localX, blockY, localZ);
                    var paletteIndex = paletteIndexes.computeIfAbsent(state, ignored -> {
                        palette.add(state);
                        return palette.size() - 1;
                    });
                    if (paletteIndex > 255) {
                        throw new IOException("Prepared chunks currently support at most 256 block states");
                    }
                    blocks[index++] = (byte) (int) paletteIndex;
                }
            }
        }

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
                output.writeUTF(state.identifier());
                output.writeInt(state.properties().size());
                for (var property : state.properties().entrySet()) {
                    output.writeUTF(property.getKey());
                    output.writeUTF(property.getValue());
                }
            }
            output.write(blocks);
        }
        return bytes.toByteArray();
    }

    private GeneratedChunk decodeSnapshot(byte[] snapshot) throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(snapshot))) {
            if (input.readInt() != SNAPSHOT_MAGIC || input.readInt() != SNAPSHOT_VERSION) {
                throw new IOException("Unsupported prepared chunk snapshot");
            }
            var position = new ChunkPosition(input.readInt(), input.readInt());
            var minY = input.readInt();
            var height = input.readInt();
            var paletteSize = input.readInt();
            if (paletteSize < 1 || paletteSize > 256) {
                throw new IOException("Invalid prepared chunk palette size");
            }
            var palette = new ArrayList<BlockState>(paletteSize);
            for (var paletteIndex = 0; paletteIndex < paletteSize; paletteIndex++) {
                var identifier = input.readUTF();
                var propertyCount = input.readInt();
                var properties = new LinkedHashMap<String, String>();
                for (var propertyIndex = 0; propertyIndex < propertyCount; propertyIndex++) {
                    properties.put(input.readUTF(), input.readUTF());
                }
                palette.add(new BlockState(identifier, properties));
            }
            var blocks = input.readNBytes(IChunk.WIDTH * IChunk.WIDTH * height);
            if (blocks.length != IChunk.WIDTH * IChunk.WIDTH * height) {
                throw new IOException("Truncated prepared chunk snapshot");
            }
            return new GeneratedChunk(position, minY, height, palette, blocks);
        }
    }
}
