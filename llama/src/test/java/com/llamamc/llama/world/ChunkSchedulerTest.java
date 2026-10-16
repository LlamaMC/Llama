package com.llamamc.llama.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class ChunkSchedulerTest {

    @Test
    void closeFinishesQuicklyWhenNothingIsQueued() {
        var scheduler = new ChunkScheduler("test");
        var started = System.nanoTime();

        assertTrue(scheduler.close(Duration.ofSeconds(30)));
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(5).toNanos());
    }

    @Test
    void taskBlockedOnLatchIsDroppedWhenTheBudgetExpires() throws InterruptedException {
        var scheduler = new ChunkScheduler("test");
        var generation = (ThreadPoolExecutor) scheduler.generation();
        var workers = generation.getMaximumPoolSize();
        var running = new CountDownLatch(workers);
        var release = new CountDownLatch(1);
        var queuedRuns = new AtomicInteger();
        for (var i = 0; i < workers; i++) {
            generation.execute(() -> blockUntilReleased(running, release));
        }
        for (var i = 0; i < 5; i++) {
            generation.execute(queuedRuns::incrementAndGet);
        }
        assertTrue(running.await(5, TimeUnit.SECONDS));

        assertFalse(scheduler.close(Duration.ofMillis(50)));
        assertEquals(0, queuedRuns.get());
    }

    private static void blockUntilReleased(CountDownLatch running, CountDownLatch release) {
        running.countDown();
        try {
            release.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
