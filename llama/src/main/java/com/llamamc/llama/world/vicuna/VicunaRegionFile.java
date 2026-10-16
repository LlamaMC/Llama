package com.llamamc.llama.world.vicuna;

import com.llamamc.llamaapi.world.storage.ChunkPayload;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final long DATA_OFFSET = (long) DATA_SECTOR * SECTOR_BYTES;
    private static final int FIRST_COMMIT_OFFSET = 32;
    private static final int COMMIT_BYTES = 24;
    private static final long MIN_COMPACTION_BYTES = 1024L * 1024L;
    private static final String TEMPORARY_SUFFIX = ".tmp";
    private static final IFileMover REPLACE_ATOMICALLY = (source, target) ->
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    private static final IChannelOpener OPEN_FILE = FileChannel::open;

    private final Path path;
    private final int regionX;
    private final int regionZ;
    private final IFileMover mover;
    private final IChannelOpener opener;

    private FileChannel channel;
    private Entry[] entries;
    private long generation;
    private int activeDirectory;
    // Bytes of the data area that no directory entry references, counted in whole sectors.
    private long deadBytes;
    // Set when reopening the region file failed, so the channel and entries no longer match the file on disk.
    private IOException reopenFailure;

    VicunaRegionFile(Path path, int regionX, int regionZ) throws IOException {
        this(path, regionX, regionZ, REPLACE_ATOMICALLY, OPEN_FILE);
    }

    VicunaRegionFile(Path path, int regionX, int regionZ, IFileMover mover) throws IOException {
        this(path, regionX, regionZ, mover, OPEN_FILE);
    }

    VicunaRegionFile(Path path, int regionX, int regionZ, IFileMover mover, IChannelOpener opener) throws IOException {
        this.path = path;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.mover = mover;
        this.opener = opener;
        Files.createDirectories(path.getParent());
        // A temporary file is only left by a compaction that did not reach the move, so the region file stays the authority.
        Files.deleteIfExists(temporaryPath(path));
        channel = opener.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);

        if (channel.size() == 0) {
            initialize();
            return;
        }

        openExisting();
    }

    synchronized Optional<ChunkPayload> read(int index) throws IOException {
        checkIndex(index);
        requireUsable();
        var entry = entries[index];
        if (entry.empty()) {
            return Optional.empty();
        }

        var bytes = readVerified(entry);
        var compression = VicunaCompression.byId(entry.compression());
        if (compression == VicunaCompression.NONE && entry.rawLength() != bytes.length) {
            throw new IOException("Invalid uncompressed VICUÑA chunk length");
        }

        return Optional.of(new ChunkPayload(bytes));
    }

    synchronized void write(int index, ChunkPayload payload) throws IOException {
        checkIndex(index);
        requireUsable();
        var bytes = payload.nbt();
        var offset = align(channel.size());
        writeFully(ByteBuffer.wrap(bytes), offset);
        padAfter(offset + bytes.length);
        channel.force(false);

        var replaced = entries[index];
        var nextEntries = entries.clone();
        nextEntries[index] = new Entry(offset, bytes.length, bytes.length, generation + 1,
                VicunaCompression.NONE.id(), 0, checksum(bytes));
        commit(nextEntries, generation + 1);
        deadBytes += footprint(replaced);
    }

    synchronized long deadBytes() {
        return deadBytes;
    }

    synchronized boolean failed() {
        return reopenFailure != null;
    }

    synchronized void compactIfNeeded() throws IOException {
        requireUsable();
        var size = channel.size();
        if (size < MIN_COMPACTION_BYTES || deadBytes * 2 <= size) {
            return;
        }

        compact();
    }

    // The old file stays authoritative until the move succeeds, so a crash or a failed move leaves a readable region.
    synchronized void compact() throws IOException {
        requireUsable();
        var temporary = temporaryPath(path);
        Files.deleteIfExists(temporary);
        var rewritten = copyLiveTo(temporary);
        try {
            // Windows cannot replace a file that is open, so the old channel closes before the move.
            channel.close();
            mover.move(temporary, path);
        } catch (IOException error) {
            discard(temporary, error);
            reopenAfterFailedMove(error);
            throw error;
        }

        try {
            channel = opener.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (IOException error) {
            // The file was replaced, but the entries still point into the old one, so nothing may read through this region.
            reopenFailure = error;
            throw error;
        }
        entries = rewritten.entries;
        generation = rewritten.generation;
        activeDirectory = rewritten.activeDirectory;
        deadBytes = 0;
        syncDirectory(path.getParent());
    }

    @Override
    public synchronized void close() throws IOException {
        channel.close();
    }

    private VicunaRegionFile copyLiveTo(Path temporary) throws IOException {
        try (var rewritten = new VicunaRegionFile(temporary, regionX, regionZ, mover, opener)) {
            rewritten.copyLiveFrom(this);
            return rewritten;
        } catch (IOException | RuntimeException error) {
            discard(temporary, error);
            throw error;
        }
    }

    private void copyLiveFrom(VicunaRegionFile source) throws IOException {
        var nextEntries = emptyEntries();
        var offset = DATA_OFFSET;
        for (var index = 0; index < CHUNK_COUNT; index++) {
            var entry = source.entries[index];
            if (entry.empty()) {
                continue;
            }

            var bytes = source.readVerified(entry);
            writeFully(ByteBuffer.wrap(bytes), offset);
            padAfter(offset + bytes.length);
            nextEntries[index] = new Entry(offset, entry.storedLength(), entry.rawLength(), entry.generation(),
                    entry.compression(), entry.flags(), entry.checksum());
            offset = align(offset + bytes.length);
        }
        channel.force(false);
        commit(nextEntries, source.generation + 1);
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
        padTo(DATA_OFFSET);
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
        deadBytes = channel.size() - DATA_OFFSET - liveBytes(entries);
    }

    // Writes the new payloads' directory into the inactive copy, then commits it into the alternating slot.
    private void commit(Entry[] nextEntries, long nextGeneration) throws IOException {
        var nextDirectory = 1 - activeDirectory;
        var directory = encodeDirectory(nextEntries);
        var directoryChecksum = checksum(directory.array());
        writeFully(directory, directoryOffset(nextDirectory));
        channel.force(false);

        var slot = Math.toIntExact(nextGeneration & 1L);
        writeFully(encodeCommit(nextGeneration, nextDirectory, directoryChecksum),
                FIRST_COMMIT_OFFSET + (long) slot * COMMIT_BYTES);
        channel.force(true);

        entries = nextEntries;
        generation = nextGeneration;
        activeDirectory = nextDirectory;
    }

    private byte[] readVerified(Entry entry) throws IOException {
        var stored = ByteBuffer.allocate(entry.storedLength());
        readFully(stored, entry.offset());
        var bytes = stored.array();
        if (checksum(bytes) != entry.checksum()) {
            throw new IOException("VICUÑA chunk checksum mismatch in region " + regionX + "," + regionZ);
        }

        return bytes;
    }

    // The move failed, so the file on disk is still the old one; only a reopen that fails leaves the region unusable.
    private void reopenAfterFailedMove(IOException failure) {
        try {
            channel = opener.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        } catch (IOException error) {
            failure.addSuppressed(error);
            reopenFailure = error;
        }
    }

    private void requireUsable() throws IOException {
        if (reopenFailure == null) {
            return;
        }

        throw new IOException("VICUÑA region " + regionX + "," + regionZ
                + " is unusable after a failed reopen and must be reopened from disk", reopenFailure);
    }

    private static void discard(Path temporary, Throwable failure) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException error) {
            failure.addSuppressed(error);
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (var directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)) {
            directoryChannel.force(true);
        }
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

    private static long liveBytes(Entry[] source) {
        var total = 0L;
        for (var entry : source) {
            total += footprint(entry);
        }
        return total;
    }

    private static long footprint(Entry entry) {
        if (entry.empty()) {
            return 0;
        }

        return align(entry.storedLength());
    }

    private static Path temporaryPath(Path regionPath) {
        return regionPath.resolveSibling(regionPath.getFileName().toString() + TEMPORARY_SUFFIX);
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

    @FunctionalInterface
    interface IFileMover {
        void move(Path source, Path target) throws IOException;
    }

    @FunctionalInterface
    interface IChannelOpener {
        FileChannel open(Path target, OpenOption... options) throws IOException;
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
