package com.llamamc.llama.world;

import com.llamamc.llama.world.vicuna.VicunaChunkStorage;
import com.llamamc.llamaapi.concurrent.LlamaPromise.LlamaAsyncException;
import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.IChunkGenerator;
import com.llamamc.llamaapi.world.IWorld;
import com.llamamc.llamaapi.world.WorldType;
import com.llamamc.llamaapi.world.storage.ChunkPayload;
import com.llamamc.llamaapi.world.storage.IChunkStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class LlamaWorldTest {
    private static final int BLOCK_X = 5;
    private static final int BLOCK_Y = 0;
    private static final int BLOCK_Z = 7;
    private static final BlockState STONE = new BlockState("minecraft:stone");
    private static final int CHUNK_MIN_Y = -64;
    private static final int CHUNK_HEIGHT = 384;
    private static final int WIDE_STATE_COUNT = 300;
    private static final int BYTE_STATE_COUNT = 200;

    @TempDir
    Path directory;

    @Test
    void deduplicatesConcurrentGenerationAndPersistsTheResult() throws Exception {
        var generations = new AtomicInteger();
        IChunkGenerator generator = (world, position) -> {
            generations.incrementAndGet();
            return new FlatChunkGenerator().generate(world, position);
        };

        try (var world = world(generator); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = IntStream.range(0, 32)
                    .mapToObj(ignored -> callers.submit(() -> world.chunkNow(4, -3)))
                    .toList();
            for (var task : tasks) {
                assertEquals("minecraft:grass_block", task.get().block(0, -61, 0).identifier());
            }
            assertEquals(1, generations.get());
        }

        IChunkGenerator failingGenerator = (world, position) -> {
            throw new AssertionError("Persisted chunks must not regenerate");
        };
        try (var world = world(failingGenerator)) {
            var chunk = world.chunkNow(4, -3);
            assertEquals(new ChunkPosition(4, -3), chunk.position());
            assertEquals("minecraft:bedrock", chunk.block(0, -64, 0).identifier());
            assertEquals(-61, chunk.highestBlockY(15, 15));
        }
    }

    @Test
    void regeneratesChunksWhenTheGeneratorVersionChanges() throws Exception {
        var generations = new AtomicInteger();
        try (var world = world(versionedGenerator(1, generations))) {
            world.chunkNow(0, 0);
        }
        try (var world = world(versionedGenerator(2, generations))) {
            world.chunkNow(0, 0);
        }
        try (var world = world(versionedGenerator(2, generations))) {
            world.chunkNow(0, 0);
        }

        assertEquals(2, generations.get());
    }

    @Test
    @Timeout(10)
    void chunkNowThrowsOnTickThread() throws Exception {
        try (var scheduler = new ChunkScheduler("test");
             var world = world(failingGenerator(), scheduler, () -> true)) {
            var error = assertThrows(IllegalStateException.class, () -> world.chunkNow(0, 0));
            assertTrue(error.getMessage().contains("chunk()"));
        }
    }

    @Test
    @Timeout(10)
    void chunkDoesNotBlockOnTickThread() throws Exception {
        var release = new CountDownLatch(1);
        IChunkGenerator gated = (world, position) -> {
            awaitRelease(release);
            return new FlatChunkGenerator().generate(world, position);
        };
        try (var scheduler = new ChunkScheduler("test");
             var world = world(gated, scheduler, () -> true)) {
            var future = world.chunk(0, 0);
            assertFalse(future.isDone());
            release.countDown();
            assertEquals(new ChunkPosition(0, 0), future.await().position());
        }
    }

    @Test
    @Timeout(10)
    void chunkAfterCloseFails() throws Exception {
        var world = world(failingGenerator());
        world.close();
        assertThrows(LlamaAsyncException.class, () -> world.chunk(0, 0).await());
    }

    @Test
    @Timeout(10)
    void closedWorldFailsChunkEvenWhenAlreadyLoaded() throws Exception {
        var world = world(new FlatChunkGenerator());
        world.chunkNow(0, 0);
        world.close();
        assertThrows(LlamaAsyncException.class, () -> world.chunk(0, 0).join());
    }

    @Test
    @Timeout(10)
    void chunkNowFromAGenerationThreadFails() throws Exception {
        IChunkGenerator nested = (world, position) -> {
            world.chunkNow(1, 0);
            return new FlatChunkGenerator().generate(world, position);
        };
        try (var world = world(nested)) {
            var error = assertThrows(LlamaAsyncException.class, () -> world.chunk(0, 0).join());
            assertInstanceOf(IllegalStateException.class, error.getCause());
        }
    }

    @Test
    @Timeout(10)
    void loadedChunkIsEmptyBeforeLoad() throws Exception {
        try (var world = world(new FlatChunkGenerator())) {
            assertTrue(world.loadedChunk(0, 0).isEmpty());
            world.chunkNow(0, 0);
            assertTrue(world.loadedChunk(0, 0).isPresent());
        }
    }

    @Test
    @Timeout(10)
    void unloadChunkRemovesALoadedChunkAndAReloadReturnsTheSameBlocks() throws Exception {
        var generations = new AtomicInteger();
        IChunkGenerator counting = (world, position) -> {
            generations.incrementAndGet();
            return new FlatChunkGenerator().generate(world, position);
        };
        try (var world = world(counting)) {
            var chunk = world.chunkNow(0, 0);
            var identifier = chunk.block(0, chunk.minY(), 0).identifier();

            assertTrue(world.unloadChunk(0, 0));
            assertTrue(world.loadedChunk(0, 0).isEmpty());

            var reloaded = world.chunk(0, 0).join();
            assertEquals(identifier, reloaded.block(0, reloaded.minY(), 0).identifier());
            // A reload from storage must not generate the chunk again.
            assertEquals(1, generations.get());
        }
    }

    @Test
    @Timeout(10)
    void unloadChunkReturnsFalseForAnUnloadedChunk() throws Exception {
        try (var world = world(new FlatChunkGenerator())) {
            assertFalse(world.unloadChunk(5, 5));
        }
    }

    @Test
    @Timeout(10)
    void unloadChunkReturnsFalseOnceTheWorldIsClosed() throws Exception {
        var world = world(new FlatChunkGenerator());
        world.chunkNow(0, 0);
        world.close();
        assertFalse(world.unloadChunk(0, 0));
    }

    @Test
    @Timeout(10)
    void closeFlushesADirtyChunkBeforeTheStorageCloses() throws Exception {
        try (var scheduler = new ChunkScheduler("test")) {
            var world = world(new FlatChunkGenerator(), new VicunaChunkStorage(directory), scheduler, () -> true);
            world.chunk(0, 0).join();
            world.setBlock(BLOCK_X, BLOCK_Y, BLOCK_Z, STONE);

            assertTrue(world.close(Duration.ofSeconds(5)));
        }

        // The reopened world reads the chunk from storage. A regeneration would fail the test.
        try (var reopened = world(failingGenerator())) {
            assertEquals(STONE, reopened.chunkNow(0, 0).block(BLOCK_X, BLOCK_Y, BLOCK_Z));
        }
    }

    @Test
    @Timeout(10)
    void aChunkThatFailsToSaveKeepsCloseUnclean() throws Exception {
        var storage = new FailingWritesStorage(new VicunaChunkStorage(directory));
        try (var scheduler = new ChunkScheduler("test")) {
            var world = world(new FlatChunkGenerator(), storage, scheduler, () -> true);
            world.chunk(0, 0).join();
            world.setBlock(BLOCK_X, BLOCK_Y, BLOCK_Z, STONE);

            storage.failWrites();
            assertFalse(world.close(Duration.ofSeconds(5)));
            assertTrue(world.entryOf(0, 0).orElseThrow().dirty());
        }
    }

    @Test
    @Timeout(20)
    void savesADemotedPaletteAndReloadsTheSameBlocks() throws Exception {
        var states = syntheticStates(WIDE_STATE_COUNT);
        IChunkGenerator generator = (world, position) -> new GeneratedChunk(position, CHUNK_MIN_Y, CHUNK_HEIGHT,
                states, cyclingIndexes());

        try (var scheduler = new ChunkScheduler("test")) {
            var world = world(generator, new VicunaChunkStorage(directory), scheduler, () -> true);
            world.chunk(0, 0).join();
            // Block index 299 takes state 0. State 299 is still used by other blocks, so all 300 states remain.
            world.setBlock(11, CHUNK_MIN_Y + 1, 2, states.get(0));
            assertTrue(world.close(Duration.ofSeconds(5)));
        }
        assertEquals(5025, storedDataVersion());

        try (var scheduler = new ChunkScheduler("test")) {
            var world = world(failingGenerator(), new VicunaChunkStorage(directory), scheduler, () -> true);
            world.chunk(0, 0).join();
            for (var blockY = CHUNK_MIN_Y; blockY < CHUNK_MIN_Y + CHUNK_HEIGHT; blockY++) {
                for (var localZ = 0; localZ < IChunk.WIDTH; localZ++) {
                    for (var localX = 0; localX < IChunk.WIDTH; localX++) {
                        var stateIndex = flatIndex(localX, blockY, localZ) % WIDE_STATE_COUNT;
                        if (stateIndex >= BYTE_STATE_COUNT) {
                            world.setBlock(localX, blockY, localZ, states.get(stateIndex - BYTE_STATE_COUNT));
                        }
                    }
                }
            }
            assertTrue(world.close(Duration.ofSeconds(5)));
        }
        assertEquals(5024, storedDataVersion());

        try (var reopened = world(failingGenerator())) {
            var chunk = reopened.chunkNow(0, 0);
            for (var blockY = CHUNK_MIN_Y; blockY < CHUNK_MIN_Y + CHUNK_HEIGHT; blockY++) {
                for (var localZ = 0; localZ < IChunk.WIDTH; localZ++) {
                    for (var localX = 0; localX < IChunk.WIDTH; localX++) {
                        var stateIndex = flatIndex(localX, blockY, localZ) % WIDE_STATE_COUNT;
                        var expected = states.get(stateIndex < BYTE_STATE_COUNT
                                ? stateIndex : stateIndex - BYTE_STATE_COUNT);
                        assertEquals(expected, chunk.block(localX, blockY, localZ));
                    }
                }
            }
        }
    }

    private static List<BlockState> syntheticStates(int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> new BlockState("minecraft:test_block", Map.of("index", Integer.toString(index))))
                .toList();
    }

    // Block index i of the generated chunk holds state i modulo the state count.
    private static short[] cyclingIndexes() {
        var indexes = new short[IChunk.WIDTH * IChunk.WIDTH * CHUNK_HEIGHT];
        for (var index = 0; index < indexes.length; index++) {
            indexes[index] = (short) (index % WIDE_STATE_COUNT);
        }
        return indexes;
    }

    private static int flatIndex(int localX, int blockY, int localZ) {
        return (blockY - CHUNK_MIN_Y) * IChunk.WIDTH * IChunk.WIDTH + localZ * IChunk.WIDTH + localX;
    }

    private int storedDataVersion() throws IOException {
        try (var storage = new VicunaChunkStorage(directory)) {
            return dataVersion(storage.read(new ChunkPosition(0, 0)).orElseThrow());
        }
    }

    private static int dataVersion(ChunkPayload payload) throws IOException {
        var input = new DataInputStream(new ByteArrayInputStream(payload.nbt()));
        input.readUnsignedByte();
        input.readUTF();
        input.readUnsignedByte();
        assertEquals("DataVersion", input.readUTF());
        return input.readInt();
    }

    private IChunkGenerator versionedGenerator(int version, AtomicInteger generations) {
        return new IChunkGenerator() {
            @Override
            public IChunk generate(IWorld world, ChunkPosition position) {
                generations.incrementAndGet();
                return new FlatChunkGenerator().generate(world, position);
            }

            @Override
            public int generationVersion() {
                return version;
            }
        };
    }

    private IChunkGenerator failingGenerator() {
        return (world, position) -> {
            throw new AssertionError("Generation must not run");
        };
    }

    // Bounded so a regression that generates inline fails the test instead of hanging it.
    private static void awaitRelease(CountDownLatch release) {
        try {
            if (release.await(5, TimeUnit.SECONDS)) {
                return;
            }
            throw new IllegalStateException("Generation was never released");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private LlamaWorld world(IChunkGenerator generator) {
        return new LlamaWorld("test", "1234", directory, DimensionType.OVERWORLD, WorldType.FLAT,
                generator, new VicunaChunkStorage(directory));
    }

    private LlamaWorld world(IChunkGenerator generator, ChunkScheduler scheduler, BooleanSupplier onTickThread) {
        return new LlamaWorld("test", "1234", directory, DimensionType.OVERWORLD, WorldType.FLAT,
                generator, new VicunaChunkStorage(directory), scheduler, onTickThread);
    }

    private LlamaWorld world(IChunkGenerator generator, IChunkStorage storage, ChunkScheduler scheduler,
                             BooleanSupplier onTickThread) {
        return new LlamaWorld("test", "1234", directory, DimensionType.OVERWORLD, WorldType.FLAT,
                generator, storage, scheduler, onTickThread);
    }

    // Storage whose writes fail once the test asks for it. Reads and close go to the delegate.
    private static final class FailingWritesStorage implements IChunkStorage {
        private final IChunkStorage delegate;
        private volatile boolean failing;

        FailingWritesStorage(IChunkStorage delegate) {
            this.delegate = delegate;
        }

        void failWrites() {
            failing = true;
        }

        @Override
        public Optional<ChunkPayload> read(ChunkPosition position) throws IOException {
            return delegate.read(position);
        }

        @Override
        public void write(ChunkPosition position, ChunkPayload payload) throws IOException {
            if (failing) {
                throw new IOException("Disk full");
            }
            delegate.write(position, payload);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
