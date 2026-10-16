package com.llamamc.llama.tick;

import com.llamamc.llamaapi.concurrent.LlamaFuture;
import com.llamamc.llamaapi.concurrent.LlamaPromise.LlamaAsyncException;
import com.llamamc.llamaapi.scheduler.ITaskHandle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class TickSchedulerTest {
    private final List<Throwable> errors = new CopyOnWriteArrayList<>();
    private final TickLoop loop = new TickLoop(() -> 0, (_, error) -> errors.add(error));
    private final TickScheduler scheduler = new TickScheduler(loop);
    private final CompletableFuture<Void> tickGate = new CompletableFuture<>();

    @AfterEach
    void stop() {
        tickGate.complete(null);
        loop.stop(Duration.ofSeconds(5));
    }

    @Test
    void runOnTickRunsInFifoOrderOnTheTickThread() {
        List<String> log = new CopyOnWriteArrayList<>();
        loop.start();
        scheduler.runOnTick(() -> log.add("first:" + scheduler.isTickThread()));
        scheduler.runOnTick(() -> log.add("second:" + scheduler.isTickThread()));
        scheduler.runOnTick(() -> log.add("third:" + scheduler.isTickThread())).join();
        assertEquals(List.of("first:true", "second:true", "third:true"), log);
    }

    @Test
    void callOnTickCompletesWithTheResult() {
        loop.start();
        assertEquals("value", scheduler.callOnTick(() -> "value").join());
    }

    @Test
    void callOnTickFailsWhenTheTaskThrows() {
        loop.start();
        LlamaFuture<Object> failed = scheduler.callOnTick(() -> {
            throw new IllegalStateException("boom");
        });
        var error = assertThrows(LlamaAsyncException.class, failed::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test
    void runLaterTicksRunsAtLeastThreeTicksAfterScheduling() throws Exception {
        CompletableFuture<Long> ranAtTick = new CompletableFuture<>();
        loop.start();
        long scheduledAtTick = loop.tickCount();
        scheduler.runLaterTicks(3, () -> ranAtTick.complete(loop.tickCount()));
        assertTrue(ranAtTick.get(5, TimeUnit.SECONDS) - scheduledAtTick >= 3);
    }

    @Test
    void cancelledTaskNeverRuns() throws Exception {
        AtomicBoolean cancelledRan = new AtomicBoolean();
        CompletableFuture<Long> laterMarker = new CompletableFuture<>();
        loop.start();
        // The gate holds the tick thread, so the delayed task is cancelled before the loop can queue it.
        scheduler.runOnTick(tickGate::join);
        ITaskHandle handle = scheduler.runLaterTicks(5, () -> cancelledRan.set(true));
        handle.cancel();
        tickGate.complete(null);
        scheduler.runLaterTicks(6, () -> laterMarker.complete(loop.tickCount()));

        laterMarker.get(5, TimeUnit.SECONDS);
        assertFalse(cancelledRan.get());
        assertTrue(handle.isCancelled());
        assertTrue(errors.isEmpty());
    }

    @Test
    void runOnTickAfterStopFailsAtOnce() {
        loop.start();
        assertTrue(loop.stop(Duration.ofSeconds(2)));
        LlamaFuture<Void> late = scheduler.runOnTick(() -> {});
        assertTrue(late.isDone());
        var error = assertThrows(LlamaAsyncException.class, late::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test
    @Timeout(5)
    void queuedCallOnTickFailsWhenTheSchedulerCloses() {
        LlamaFuture<String> queued = scheduler.callOnTick(() -> "value");
        scheduler.close();
        assertTrue(queued.isDone());
        var error = assertThrows(LlamaAsyncException.class, queued::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test
    @Timeout(10)
    void queuedCallOnTickNeverRunsWhenTheSchedulerClosesBeforeStart() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        CountDownLatch markerRan = new CountDownLatch(1);
        LlamaFuture<String> queued = scheduler.callOnTick(() -> {
            invoked.set(true);
            return "value";
        });
        scheduler.close();
        loop.start();
        assertTrue(loop.execute(markerRan::countDown));
        assertTrue(markerRan.await(5, TimeUnit.SECONDS));

        assertTrue(queued.isDone());
        var error = assertThrows(LlamaAsyncException.class, queued::join);
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertFalse(invoked.get());
    }

    @Test
    @Timeout(5)
    void runOnTickAfterCloseFailsAtOnce() {
        scheduler.close();
        LlamaFuture<Void> late = scheduler.runOnTick(() -> {});
        assertTrue(late.isDone());
        assertThrows(LlamaAsyncException.class, late::join);
    }

    @Test
    @Timeout(5)
    void runLaterTicksAfterCloseIsCancelledAtOnce() {
        scheduler.close();
        ITaskHandle handle = scheduler.runLaterTicks(3, () -> {});
        assertTrue(handle.isCancelled());
    }

    @Test
    @Timeout(5)
    void queuedRunLaterTicksIsCancelledWhenTheSchedulerCloses() {
        ITaskHandle handle = scheduler.runLaterTicks(3, () -> {});
        scheduler.close();
        assertTrue(handle.isCancelled());
    }

    @Test
    void supplyAsyncNeverRunsOnTheTickThread() {
        loop.start();
        Thread worker = scheduler.supplyAsync(Thread::currentThread).join();
        assertTrue(worker.isVirtual());
        assertNotSame(loop.tickThread(), worker);
    }
}
