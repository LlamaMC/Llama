package com.llamamc.llama.world;

import com.llamamc.llama.world.vicuna.VicunaChunkStorage;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.IChunkGenerator;
import com.llamamc.llamaapi.world.IWorld;
import com.llamamc.llamaapi.world.WorldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlamaWorldTest {
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
            var tasks = java.util.stream.IntStream.range(0, 32)
                    .mapToObj(ignored -> callers.submit(() -> world.chunk(4, -3)))
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
            var chunk = world.chunk(4, -3);
            assertEquals(new ChunkPosition(4, -3), chunk.position());
            assertEquals("minecraft:bedrock", chunk.block(0, -64, 0).identifier());
            assertEquals(-61, chunk.highestBlockY(15, 15));
        }
    }

    @Test
    void regeneratesChunksWhenTheGeneratorVersionChanges() throws Exception {
        var generations = new AtomicInteger();
        try (var world = world(versionedGenerator(1, generations))) {
            world.chunk(0, 0);
        }
        try (var world = world(versionedGenerator(2, generations))) {
            world.chunk(0, 0);
        }
        try (var world = world(versionedGenerator(2, generations))) {
            world.chunk(0, 0);
        }

        assertEquals(2, generations.get());
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

    private LlamaWorld world(IChunkGenerator generator) {
        return new LlamaWorld("test", "1234", directory, DimensionType.OVERWORLD, WorldType.FLAT,
                generator, new VicunaChunkStorage(directory));
    }
}
