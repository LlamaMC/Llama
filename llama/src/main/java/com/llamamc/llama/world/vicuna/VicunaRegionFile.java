package com.llamamc.llama.world.vicuna;

import com.llamamc.llamaapi.world.storage.ChunkPayload;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.zip.CRC32C;

final class VicunaRegionFile implements AutoCloseable {
    private static final int MAGIC = 0x56434E41;
    private static final int FORMAT_VERSION = 1;
    private static final int SECTOR_BYTES = 4096;
    private static final int CHUNK_COUNT = 1024;
    private static final int ENTRY_BYTES = 32;
    private static final int DIRECTORY_BYTES = CHUNK_COUNT * ENTRY_BYTES;
    private static final int FIRST_DIRECTORY_SECTOR = 1;
    private static final int DIRECTORY_SECTORS = DIRECTORY_BYTES / SECTOR_BYTES;
    private static final int DATA_SECTOR = FIRST_DIRECTORY_SECTOR + DIRECTORY_SECTORS * 2;
    private static final int FIRST_COMMIT_OFFSET = 32;
    private static final int COMMIT_BYTES = 24;

    private final FileChannel channel;
    private final int regionX;
    private final int regionZ;

    private Entry[] entries;
    private long generation;
    private int activeDirectory;

    VicunaRegionFile(Path path, int regionX, int regionZ) throws IOException {
        this.regionX = regionX;
        this.regionZ = regionZ;
        Files.createDirectories(path.getParent());
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

        if (channel.size() == 0) {
            initialize();
            return;
        }

        openExisting();
    }

    synchronized Optional<ChunkPayload> read(int index) throws IOException {
        checkIndex(index);
        var entry = entries[index];
        if (entry.empty()) {
            return Optional.empty();
        }

        var stored = ByteBuffer.allocate(entry.storedLength());
        readFully(stored, entry.offset());
        var bytes = stored.array();
        if (checksum(bytes) != entry.checksum()) {
            throw new IOException("VICUÑA chunk checksum mismatch in region " + regionX + "," + regionZ);
        }

        var compression = VicunaCompression.byId(entry.compression());
        if (compression == VicunaCompression.NONE && entry.rawLength() != bytes.length) {
            throw new IOException("Invalid uncompressed VICUÑA chunk length");
        }

        return Optional.of(new ChunkPayload(bytes));
    }

    synchronized void write(int index, ChunkPayload payload) throws IOException {
        checkIndex(index);
        var bytes = payload.nbt();
        var offset = align(channel.size());
        writeFully(ByteBuffer.wrap(bytes), offset);
        padAfter(offset + bytes.length);
        channel.force(false);

        var nextEntries = entries.clone();
        nextEntries[index] = new Entry(offset, bytes.length, bytes.length, generation + 1,
                VicunaCompression.NONE.id(), 0, checksum(bytes));
        var nextDirectory = 1 - activeDirectory;
        var directory = encodeDirectory(nextEntries);
        var directoryChecksum = checksum(directory.array());
        writeFully(directory, directoryOffset(nextDirectory));
        channel.force(false);

        var nextGeneration = generation + 1;
        var slot = Math.toIntExact(nextGeneration & 1L);
        writeFully(encodeCommit(nextGeneration, nextDirectory, directoryChecksum),
                FIRST_COMMIT_OFFSET + (long) slot * COMMIT_BYTES);
        channel.force(true);

        entries = nextEntries;
        generation = nextGeneration;
        activeDirectory = nextDirectory;
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }

    private void initialize() throws IOException {
        entries = emptyEntries();
        generation = 0;
        activeDirectory = 0;

        var header = ByteBuffer.allocate(SECTOR_BYTES).order(ByteOrder.BIG_ENDIAN);
        header.putInt(MAGIC);
        header.putInt(FORMAT_VERSION);
        header.putInt(regionX);
        header.putInt(regionZ);
        header.putInt(SECTOR_BYTES);
        header.putInt(ENTRY_BYTES);
        header.putInt(CHUNK_COUNT);
        header.position(0);
        writeFully(header, 0);

        var directory = encodeDirectory(entries);
        var directoryChecksum = checksum(directory.array());
        writeFully(directory, directoryOffset(0));
        writeFully(encodeDirectory(entries), directoryOffset(1));
        writeFully(encodeCommit(0, 0, directoryChecksum), FIRST_COMMIT_OFFSET);
        padTo((long) DATA_SECTOR * SECTOR_BYTES);
        channel.force(true);
    }

    private void openExisting() throws IOException {
        var header = ByteBuffer.allocate(SECTOR_BYTES).order(ByteOrder.BIG_ENDIAN);
        readFully(header, 0);
        header.flip();
        if (header.getInt() != MAGIC) {
            throw new IOException("Not a VICUÑA region file");
        }
        if (header.getInt() != FORMAT_VERSION) {
            throw new IOException("Unsupported VICUÑA format version");
        }
        if (header.getInt() != regionX || header.getInt() != regionZ) {
            throw new IOException("VICUÑA region coordinates do not match its file name");
        }
        if (header.getInt() != SECTOR_BYTES || header.getInt() != ENTRY_BYTES || header.getInt() != CHUNK_COUNT) {
            throw new IOException("Invalid VICUÑA region geometry");
        }

        Commit selected = null;
        for (var slot = 0; slot < 2; slot++) {
            var commit = readCommit(slot);
            if (commit == null || !validDirectory(commit)) {
                continue;
            }
            if (selected == null || commit.generation() > selected.generation()) {
                selected = commit;
            }
        }
        if (selected == null) {
            throw new IOException("VICUÑA has no valid committed directory");
        }

        generation = selected.generation();
        activeDirectory = selected.directory();
        entries = decodeDirectory(readDirectory(activeDirectory));
    }

    private Commit readCommit(int slot) throws IOException {
        var buffer = ByteBuffer.allocate(COMMIT_BYTES).order(ByteOrder.BIG_ENDIAN);
        readFully(buffer, FIRST_COMMIT_OFFSET + (long) slot * COMMIT_BYTES);
        var bytes = buffer.array();
        buffer.flip();
        var commit = new Commit(buffer.getLong(), buffer.getInt(), buffer.getInt(), buffer.getInt());
        if (commit.slotChecksum() != checksum(bytes, 0, 16)) {
            return null;
        }
        if (commit.directory() < 0 || commit.directory() > 1) {
            return null;
        }

        return commit;
    }

    private boolean validDirectory(Commit commit) throws IOException {
        return checksum(readDirectory(commit.directory()).array()) == commit.directoryChecksum();
    }

    private ByteBuffer readDirectory(int directory) throws IOException {
        var buffer = ByteBuffer.allocate(DIRECTORY_BYTES).order(ByteOrder.BIG_ENDIAN);
        readFully(buffer, directoryOffset(directory));
        buffer.flip();
        return buffer;
    }

    private static Entry[] decodeDirectory(ByteBuffer buffer) {
        var result = new Entry[CHUNK_COUNT];
        for (var index = 0; index < CHUNK_COUNT; index++) {
            result[index] = new Entry(buffer.getLong(), buffer.getInt(), buffer.getInt(), buffer.getLong(),
                    Byte.toUnsignedInt(buffer.get()), Byte.toUnsignedInt(buffer.get()), buffer.getInt());
            buffer.getShort();
        }
        return result;
    }

    private static ByteBuffer encodeDirectory(Entry[] source) {
        var buffer = ByteBuffer.allocate(DIRECTORY_BYTES).order(ByteOrder.BIG_ENDIAN);
        for (var entry : source) {
            buffer.putLong(entry.offset());
            buffer.putInt(entry.storedLength());
            buffer.putInt(entry.rawLength());
            buffer.putLong(entry.generation());
            buffer.put((byte) entry.compression());
            buffer.put((byte) entry.flags());
            buffer.putInt(entry.checksum());
            buffer.putShort((short) 0);
        }
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer encodeCommit(long generation, int directory, int directoryChecksum) {
        var buffer = ByteBuffer.allocate(COMMIT_BYTES).order(ByteOrder.BIG_ENDIAN);
        buffer.putLong(generation);
        buffer.putInt(directory);
        buffer.putInt(directoryChecksum);
        buffer.putInt(checksum(buffer.array(), 0, 16));
        buffer.putInt(0);
        buffer.flip();
        return buffer;
    }

    private static Entry[] emptyEntries() {
        var result = new Entry[CHUNK_COUNT];
        for (var index = 0; index < result.length; index++) {
            result[index] = Entry.EMPTY;
        }
        return result;
    }

    private void readFully(ByteBuffer target, long offset) throws IOException {
        while (target.hasRemaining()) {
            var read = channel.read(target, offset + target.position());
            if (read < 0) {
                throw new EOFException("Unexpected end of VICUÑA region file");
            }
        }
    }

    private void writeFully(ByteBuffer source, long offset) throws IOException {
        var initialPosition = source.position();
        while (source.hasRemaining()) {
            channel.write(source, offset + source.position() - initialPosition);
        }
    }

    private void padAfter(long end) throws IOException {
        padTo(align(end));
    }

    private void padTo(long size) throws IOException {
        if (channel.size() >= size) {
            return;
        }

        channel.write(ByteBuffer.wrap(new byte[]{0}), size - 1);
    }

    private static long align(long value) {
        return (value + SECTOR_BYTES - 1) / SECTOR_BYTES * SECTOR_BYTES;
    }

    private static long directoryOffset(int directory) {
        return (long) (FIRST_DIRECTORY_SECTOR + directory * DIRECTORY_SECTORS) * SECTOR_BYTES;
    }

    private static int checksum(byte[] bytes) {
        return checksum(bytes, 0, bytes.length);
    }

    private static int checksum(byte[] bytes, int offset, int length) {
        var checksum = new CRC32C();
        checksum.update(bytes, offset, length);
        return (int) checksum.getValue();
    }

    private static void checkIndex(int index) {
        if (index < 0 || index >= CHUNK_COUNT) {
            throw new IllegalArgumentException("Chunk index outside VICUÑA region: " + index);
        }
    }

    private record Commit(long generation, int directory, int directoryChecksum, int slotChecksum) {
    }

    private record Entry(long offset, int storedLength, int rawLength, long generation, int compression,
                         int flags, int checksum) {
        private static final Entry EMPTY = new Entry(0, 0, 0, 0, 0, 0, 0);

        boolean empty() {
            return offset == 0;
        }
    }
}
