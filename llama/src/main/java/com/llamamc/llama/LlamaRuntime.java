package com.llamamc.llama;

import com.llamamc.llamaapi.ILlamaRuntime;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class LlamaRuntime implements ILlamaRuntime {
    private final ScheduledExecutorService tickScheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Override
    public void start() {
        running.set(true);
        tickScheduler.scheduleAtFixedRate(this::tick, 0, 50, TimeUnit.MILLISECONDS);
    }

    @Override
    public void shutdown() {
        running.set(false);
        tickScheduler.shutdown();
    }

    @Override
    public boolean isRunning() {
        return false;
    }

    @Override
    public Logger logger() {
        return null;
    }

    @Override
    public void close() {

    }

    private void tick() {
        long start = System.nanoTime();
        try {

        } finally {
            long tickTime = System.nanoTime() - start;
            if(tickTime > 50_000_000) {
                logger().warning("Long tick: " + tickTime / 1_000_000 + "ms");
            }
        }
    }

}
