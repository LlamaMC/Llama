package com.llamamc.llama.watchdog;

import com.llamamc.llama.tick.TickLoop;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class WatchdogTest {
    private final CountDownLatch releaseAll = new CountDownLatch(1);
    private final List<String> restarts = new CopyOnWriteArrayList<>();
    private TickLoop loop;
    private Watchdog watchdog;

    @AfterEach
    void tearDown() {
        releaseAll.countDown();
        if (watchdog != null) watchdog.stop();
        if (loop != null) loop.stop(Duration.ofSeconds(2));
    }

    @Test
    void hungTickIsTakenOverAndTheServerKeepsTicking() throws Exception {
        CompletableFuture<Thread> hung = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger ticksAfterHang = new AtomicInteger();
        start(settings(1, 2), () -> 0, () -> {
            if (calls.incrementAndGet() == 3) {
                hung.complete(Thread.currentThread());
                awaitRelease();
            } else if (hung.isDone()) {
                ticksAfterHang.incrementAndGet();
            }
        });

        Thread hungThread = hung.get(2, TimeUnit.SECONDS);
        await(() -> ticksAfterHang.get() >= 5);
        assertNotSame(hungThread, loop.tickThread());
        assertEquals(List.of(hungThread), loop.abandonedThreads());
        assertTrue(restarts.isEmpty());

        releaseAll.countDown();
        hungThread.join(2000);
        assertFalse(hungThread.isAlive());
    }

    @Test
    void secondHungThreadWhileTheFirstIsStillStuckRestarts() {
        start(settings(1, 5), () -> 0, this::awaitRelease);
        await(() -> !restarts.isEmpty());
        assertEquals(1, loop.abandonedThreads().size(), "first hang was taken over");
        assertTrue(restarts.getFirst().contains("too many hung tick threads"), restarts.getFirst());
    }

    @Test
    void tooManyTakeoversInTheWindowRestart() {
        AtomicInteger calls = new AtomicInteger();
        start(settings(5, 2), () -> 0, () -> {
            if (calls.incrementAndGet() % 3 == 0) {
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ignored) {
                }
            }
        });
        await(() -> !restarts.isEmpty());
        assertTrue(restarts.getFirst().contains("hung 3 times"), restarts.getFirst());
    }

    @Test
    void blockedOnLockOfAbandonedThreadRestartsImmediately() {
        ReentrantLock lock = new ReentrantLock();
        AtomicInteger calls = new AtomicInteger();
        start(settings(5, 5), () -> 0, () -> {
            if (calls.incrementAndGet() == 1) {
                lock.lock();
                awaitRelease();
            } else {
                lock.lock();
                lock.unlock();
            }
        });
        await(() -> !restarts.isEmpty());
        assertTrue(restarts.getFirst().contains("lock held by the abandoned thread"), restarts.getFirst());
    }

    @Test
    void coreErrorsAfterATakeoverRestart() {
        AtomicInteger calls = new AtomicInteger();
        start(settings(5, 5), () -> 0, () -> {
            if (calls.incrementAndGet() == 1) awaitRelease();
        });
        await(() -> loop.abandonedThreads().size() == 1);
        for (int i = 0; i < 6; i++) watchdog.reportCoreError();
        assertEquals(1, restarts.size());
        assertTrue(restarts.getFirst().contains("keeps failing"), restarts.getFirst());
    }

    @Test
    void coreErrorsWithoutATakeoverAreIgnored() {
        start(settings(5, 5), () -> 0, () -> {
        });
        for (int i = 0; i < 20; i++) watchdog.reportCoreError();
        assertTrue(restarts.isEmpty());
    }

    @Test
    void gcPausesAreNotMistakenForHangs() throws Exception {
        AtomicLong gcStart = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Void> slowTickDone = new CompletableFuture<>();
        // Everything that happens during the slow tick is reported as a GC pause.
        LongSupplier gcPause = () -> gcStart.get() == 0 ? 0 : System.nanoTime() - gcStart.get();
        start(settings(1, 1), gcPause, () -> {
            if (calls.incrementAndGet() == 2) {
                gcStart.set(System.nanoTime());
                try {
                    Thread.sleep(600);
                } catch (InterruptedException ignored) {
                }
                slowTickDone.complete(null);
            }
        });
        slowTickDone.get(3, TimeUnit.SECONDS);
        assertTrue(loop.abandonedThreads().isEmpty());
        assertTrue(restarts.isEmpty());
    }

    private void start(WatchdogSettings settings, LongSupplier gcPause, Runnable phase) {
        loop = new TickLoop(gcPause, (_, _) -> {
        });
        loop.addPhase("test", phase);
        watchdog = new Watchdog(loop, gcPause, settings, restarts::add);
        loop.start();
        watchdog.start();
    }

    private static WatchdogSettings settings(int maxAbandoned, int maxTakeovers) {
        return new WatchdogSettings(Duration.ofMillis(20), Duration.ofMillis(100), Duration.ofMillis(250),
                maxAbandoned, maxTakeovers, Duration.ofMinutes(10), 5, Duration.ofMinutes(1));
    }

    private void awaitRelease() {
        try {
            releaseAll.await();
        } catch (InterruptedException ignored) {
            try {
                releaseAll.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("condition not met in time");
            Thread.onSpinWait();
        }
    }
}
