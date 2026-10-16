package com.llamamc.llama.world;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
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
        generation = workerPool("Llama-WorldGen-" + name + "-", generationWorkers);
        storage = workerPool("Llama-WorldIO-" + name + "-", storageWorkers);
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

    // Work queues instead of running on the caller, and a rejection after shutdown fails the caller's future.
    private static ExecutorService workerPool(String name, int workers) {
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), Thread.ofPlatform().name(name, 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
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
