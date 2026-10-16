package com.llamamc.llama.world;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class ChunkScheduler implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkScheduler.class);
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

    // Storage stays open until generation has terminated, because generation tasks still hand writes to it.
    @Override
    public void close() {
        generation.shutdown();
        await(generation);
        storage.shutdown();
        await(storage);
    }

    // Work queues instead of running on the caller, and a rejection after shutdown fails the caller's future.
    private static ExecutorService workerPool(String name, int workers) {
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), workerFactory(name), new ThreadPoolExecutor.AbortPolicy());
    }

    // Each pool numbers its own threads from 0, so the names match the earlier platform-thread builder.
    private static ThreadFactory workerFactory(String prefix) {
        var counter = new AtomicInteger();
        return task -> new WorldWorkerThread(task, prefix + counter.getAndIncrement());
    }

    private static void await(ExecutorService executor) {
        try {
            if (executor.awaitTermination(SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                return;
            }
            reportDropped(executor.shutdownNow());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            reportDropped(executor.shutdownNow());
        }
    }

    // The dropped futures are failed by LlamaWorld on close, so this warning only records the count.
    private static void reportDropped(List<Runnable> dropped) {
        if (dropped.isEmpty()) {
            return;
        }
        LOGGER.warn("Dropped {} queued chunk tasks", dropped.size());
    }
}
