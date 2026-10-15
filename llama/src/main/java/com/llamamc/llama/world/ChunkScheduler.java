package com.llamamc.llama.world;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class ChunkScheduler implements AutoCloseable {
    private static final int SHUTDOWN_SECONDS = 30;

    private final ExecutorService generation;
    private final ExecutorService storage;

    ChunkScheduler(String name) {
        var processors = Runtime.getRuntime().availableProcessors();
        var generationWorkers = Math.max(1, processors - 1);
        var storageWorkers = Math.max(2, Math.min(8, processors / 4));
        generation = boundedPool("Llama-WorldGen-" + name + "-", generationWorkers, generationWorkers * 64);
        storage = boundedPool("Llama-WorldIO-" + name + "-", storageWorkers, storageWorkers * 128);
    }

    ExecutorService generation() {
        return generation;
    }

    ExecutorService storage() {
        return storage;
    }

    @Override
    public void close() {
        generation.shutdown();
        storage.shutdown();
        await(generation);
        await(storage);
    }

    private static ExecutorService boundedPool(String name, int workers, int capacity) {
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), Thread.ofPlatform().name(name, 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private static void await(ExecutorService executor) {
        try {
            if (executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                return;
            }
            executor.shutdownNow();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
