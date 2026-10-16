package com.llamamc.llama.tick;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class TickLoopTest {
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final TickLoop loop = new TickLoop(() -> 0, (_, error) -> errors.add(error));

    @AfterEach
    void stop() {
        loop.stop(Duration.ofSeconds(5));
    }

    @Test
    void ticksTwentyTimesPerSecond() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        loop.addPhase("count", ticks::incrementAndGet);
        loop.start();
        Thread.sleep(1000);
        int counted = ticks.get();
        assertTrue(counted >= 17 && counted <= 22, "ticks in one second: " + counted);
        assertEquals(counted, loop.metrics().snapshot(System.nanoTime()).totalTicks(), 1);
    }

    @Test
    void failingPhaseDoesNotStopTheLoop() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        loop.addPhase("broken", () -> {
            throw new IllegalStateException("boom");
        });
        loop.addPhase("count", ticks::incrementAndGet);
        loop.start();
        Thread.sleep(300);
        assertTrue(ticks.get() >= 3, "later phases keep running");
        assertFalse(errors.isEmpty());
        assertInstanceOf(IllegalStateException.class, errors.getFirst());
    }

    @Test
    void failingTaskDoesNotDropOtherTasks() throws Exception {
        CompletableFuture<Boolean> second = new CompletableFuture<>();
        loop.start();
        loop.execute(() -> {
            throw new IllegalStateException("boom");
        });
        loop.execute(() -> second.complete(loop.isTickThread()));
        assertTrue(second.get(2, TimeUnit.SECONDS), "task runs on the tick thread");
        assertEquals(1, errors.size());
    }

    @Test
    void stopEndsTheTickThread() {
        loop.start();
        Thread thread = loop.tickThread();
        assertTrue(loop.stop(Duration.ofSeconds(2)));
        assertFalse(thread.isAlive());
        assertFalse(loop.isRunning());
    }

    @Test
    void takeOverContinuesOnFreshThreadAndFencesTheOldOne() throws Exception {
        CompletableFuture<Thread> hungThread = new CompletableFuture<>();
        CompletableFuture<Void> release = new CompletableFuture<>();
        List<Thread> laterPhaseThreads = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        loop.addPhase("hang", () -> {
            if (calls.incrementAndGet() == 1) {
                hungThread.complete(Thread.currentThread());
                release.join();
            }
        });
        loop.addPhase("after", () -> laterPhaseThreads.add(Thread.currentThread()));
        loop.start();

        Thread hung = hungThread.get(2, TimeUnit.SECONDS);
        assertSame(hung, loop.takeOver());
        assertTrue(loop.abandonedThreads().contains(hung));
        Thread.sleep(200);
        assertTrue(laterPhaseThreads.size() >= 2, "fresh thread keeps ticking");
        assertTrue(laterPhaseThreads.stream().noneMatch(t -> t == hung));

        release.complete(null);
        hung.join(2000);
        assertFalse(hung.isAlive(), "abandoned thread is unwound once it returns");
        assertTrue(laterPhaseThreads.stream().noneMatch(t -> t == hung), "abandoned thread never runs later phases");
        assertTrue(loop.abandonedThreads().isEmpty());
    }

    @Test
    void fenceOnlyRejectsAbandonedThreads() throws Exception {
        loop.fence();
        CountDownLatch entered = new CountDownLatch(1);
        CompletableFuture<Throwable> fenced = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        loop.addPhase("hang", () -> {
            if (calls.incrementAndGet() != 1) return;
            entered.countDown();
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (InterruptedException e) {
                try {
                    loop.fence();
                    fenced.complete(null);
                } catch (Throwable t) {
                    fenced.complete(t);
                    throw t;
                }
            }
        });
        loop.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        loop.takeOver();
        assertInstanceOf(AbandonedThreadError.class, fenced.get(2, TimeUnit.SECONDS));
        loop.fence();
    }

    @Test
    void executeReturnsFalseAfterStop() {
        assertTrue(loop.execute(() -> {}), "tasks may be queued before start");
        loop.start();
        assertTrue(loop.stop(Duration.ofSeconds(2)));
        assertFalse(loop.execute(() -> {}));
    }

    @Test
    void queuedTaskDoesNotRunOnAbandonedThread() throws Exception {
        CompletableFuture<Thread> hungThread = new CompletableFuture<>();
        CompletableFuture<Void> releaseHung = new CompletableFuture<>();
        CompletableFuture<Void> releaseFresh = new CompletableFuture<>();
        CompletableFuture<Thread> ranOn = new CompletableFuture<>();
        AtomicInteger runs = new AtomicInteger();
        try {
            loop.execute(() -> {
                hungThread.complete(Thread.currentThread());
                releaseHung.join();
            });
            // Holds the fresh thread, so the queue is not drained before the abandoned thread wakes up.
            loop.execute(releaseFresh::join);
            loop.execute(() -> {
                runs.incrementAndGet();
                ranOn.complete(Thread.currentThread());
            });
            loop.start();

            Thread hung = hungThread.get(2, TimeUnit.SECONDS);
            loop.takeOver();
            releaseHung.complete(null);
            hung.join(2000);
            assertFalse(hung.isAlive(), "abandoned thread unwinds without taking queued work");

            releaseFresh.complete(null);
            Thread runner = ranOn.get(2, TimeUnit.SECONDS);
            assertNotSame(hung, runner);
            assertSame(loop.tickThread(), runner);
            loop.stop(Duration.ofSeconds(2));
            assertEquals(1, runs.get());
        } finally {
            releaseHung.complete(null);
            releaseFresh.complete(null);
        }
    }
}
