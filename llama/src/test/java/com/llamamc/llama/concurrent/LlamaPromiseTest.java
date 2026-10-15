package com.llamamc.llama.concurrent;

import com.llamamc.llamaapi.concurrent.LlamaPromise;
import com.llamamc.llamaapi.concurrent.LlamaPromise.LlamaAsyncException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(60)
class LlamaPromiseTest {

    private static final int RACE_ITERATIONS = 100_000;

    @Test
    void runsEachListenerExactlyOnceWhenRegisteredDuringCompletion() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            for (var i = 0; i < RACE_ITERATIONS; i++) {
                var value = i;
                var calls = new AtomicInteger();

                registerWhileCompleting(worker, promise -> promise.complete(value), (v, err) -> calls.incrementAndGet());

                assertEquals(1, calls.get(), "iteration " + i);
            }
        }
    }

    @Test
    void successListenerNeverSeesNullValueWhenOneWasCompleted() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            for (var i = 0; i < RACE_ITERATIONS; i++) {
                var value = i;
                var calls = new AtomicInteger();
                var malformed = new AtomicInteger();

                registerWhileCompleting(worker, promise -> promise.complete(value), (v, err) -> {
                    calls.incrementAndGet();
                    if (v == null || err != null) {
                        malformed.incrementAndGet();
                    }
                });

                assertEquals(1, calls.get(), "iteration " + i);
                assertEquals(0, malformed.get(), "iteration " + i);
            }
        }
    }

    @Test
    void failListenerSeesErrorAndNoValue() throws Exception {
        var error = new IllegalStateException("boom");
        try (var worker = Executors.newSingleThreadExecutor()) {
            for (var i = 0; i < RACE_ITERATIONS; i++) {
                var calls = new AtomicInteger();
                var malformed = new AtomicInteger();

                registerWhileCompleting(worker, promise -> promise.fail(error), (v, err) -> {
                    calls.incrementAndGet();
                    if (v != null || err != error) {
                        malformed.incrementAndGet();
                    }
                });

                assertEquals(1, calls.get(), "iteration " + i);
                assertEquals(0, malformed.get(), "iteration " + i);
            }
        }
    }

    @Test
    void throwingListenerDoesNotStopLaterListeners() {
        var promise = new LlamaPromise<String>();
        var reported = new AtomicReference<Throwable>();
        var laterCalls = new AtomicInteger();
        var thrown = new IllegalStateException("listener failed");
        var previousHandler = Thread.currentThread().getUncaughtExceptionHandler();
        Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> reported.set(error));
        try {
            promise.future().whenComplete((v, err) -> {
                throw thrown;
            });
            promise.future().whenComplete((v, err) -> laterCalls.incrementAndGet());

            promise.complete("done");
        } finally {
            Thread.currentThread().setUncaughtExceptionHandler(previousHandler);
        }

        assertEquals(1, laterCalls.get());
        assertSame(thrown, reported.get());
    }

    @Test
    void rejectingExecutorFailsDerivedFutureWhenSourceCompletesLater() {
        var source = new LlamaPromise<String>();
        Executor rejecting = task -> {
            throw new RejectedExecutionException("queue full");
        };
        var derived = source.future().thenApplyAsync(rejecting, String::length);

        source.complete("abc");

        assertTrue(derived.isDone());
        var failure = assertThrows(LlamaAsyncException.class, derived::join);
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
    }

    @Test
    void rejectingExecutorFailsDerivedComposeFutureWhenSourceAlreadyCompleted() {
        var source = new LlamaPromise<String>();
        source.complete("abc");
        Executor rejecting = task -> {
            throw new RejectedExecutionException("queue full");
        };
        var derived = source.future().thenComposeAsync(rejecting, v -> new LlamaPromise<Integer>().future());

        assertTrue(derived.isDone());
        var failure = assertThrows(LlamaAsyncException.class, derived::join);
        assertInstanceOf(RejectedExecutionException.class, failure.getCause());
    }

    // The worker completes the promise while this thread registers the listener. The barrier makes both start together.
    private static void registerWhileCompleting(
            ExecutorService worker,
            Consumer<LlamaPromise<Integer>> completion,
            BiConsumer<Integer, Throwable> listener) throws Exception {
        var promise = new LlamaPromise<Integer>();
        var start = new CyclicBarrier(2);
        var completing = worker.submit(() -> {
            start.await();
            completion.accept(promise);
            return null;
        });

        start.await();
        promise.future().whenComplete(listener);
        completing.get();
    }
}
