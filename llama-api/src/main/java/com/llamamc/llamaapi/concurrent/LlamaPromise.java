package com.llamamc.llamaapi.concurrent;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;

public final class LlamaPromise<T> {

    private final DefaultLlamaFuture<T> future = new DefaultLlamaFuture<>();

    public LlamaFuture<T> future() {
        return future;
    }

    public boolean complete(T value) {
        return future.complete(value);
    }

    public boolean fail(Throwable error) {
        return future.fail(error);
    }

    public boolean cancel() {
        return future.cancel();
    }

    private static final class DefaultLlamaFuture<T> implements LlamaFuture<T> {

        // Value, error and cancel flag are one object, so a reader never sees a partly written result.
        private final AtomicReference<Outcome<T>> outcome = new AtomicReference<>();
        private final CountDownLatch latch = new CountDownLatch(1);
        private final ConcurrentLinkedQueue<BiConsumer<? super T, ? super Throwable>> listeners = new ConcurrentLinkedQueue<>();

        boolean complete(T value) {
            return settle(new Outcome<>(value, null, false));
        }

        boolean fail(Throwable error) {
            if (error == null) {
                throw new NullPointerException("error");
            }
            return settle(new Outcome<>(null, error, false));
        }

        boolean cancel() {
            return settle(new Outcome<>(null, null, true));
        }

        // The CAS picks the single winner. The outcome is visible before the latch and listeners are released.
        private boolean settle(Outcome<T> result) {
            if (!outcome.compareAndSet(null, result)) {
                return false;
            }
            latch.countDown();
            drain();
            return true;
        }

        // Each listener is removed before it runs, so a listener runs once even when two threads drain together.
        private void drain() {
            var settled = outcome.get();
            if (settled == null) {
                return;
            }
            var listener = listeners.poll();
            while (listener != null) {
                notifyListener(listener, settled);
                listener = listeners.poll();
            }
        }

        // A throwing listener must not stop the listeners behind it, so its exception goes to the thread's handler.
        private void notifyListener(BiConsumer<? super T, ? super Throwable> listener, Outcome<T> settled) {
            try {
                listener.accept(settled.value(), settled.failure());
            } catch (Throwable t) {
                Thread.currentThread().getUncaughtExceptionHandler().uncaughtException(Thread.currentThread(), t);
            }
        }

        @Override
        public boolean isDone() {
            return outcome.get() != null;
        }

        @Override
        public boolean isCancelled() {
            var settled = outcome.get();
            return settled != null && settled.cancelled();
        }

        @Override
        public T join() {
            try {
                return await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlamaAsyncException("Interrupted while waiting", e);
            }
        }

        @Override
        public T await() throws InterruptedException {
            latch.await();
            return resultOf(outcome.get());
        }

        private T resultOf(Outcome<T> settled) {
            if (settled.cancelled()) {
                throw new LlamaCancelledException();
            }
            if (settled.error() != null) {
                throw new LlamaAsyncException("Future failed", settled.error());
            }
            return settled.value();
        }

        @Override
        public LlamaFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
            if (action == null) {
                throw new NullPointerException("action");
            }
            var settled = outcome.get();
            if (settled != null) {
                notifyListener(action, settled);
                return this;
            }
            listeners.add(action);
            // Added before this second check, so a concurrent settle either drains the listener or is seen here.
            if (isDone()) {
                drain();
            }
            return this;
        }

        // Inline on purpose: fn runs on the thread that settles this future, or on the calling thread when it is
        // already settled, as CompletableFuture's non-async stages do. Callers that need another thread use
        // thenApplyAsync with a named executor.
        @Override
        public <U> LlamaFuture<U> thenApply(Function<? super T, ? extends U> fn) {
            return thenApplyAsync(Runnable::run, fn);
        }

        @Override
        public <U> LlamaFuture<U> thenApplyAsync(Executor executor, Function<? super T, ? extends U> fn) {
            if (executor == null) {
                throw new NullPointerException("executor");
            }
            if (fn == null) {
                throw new NullPointerException("fn");
            }
            var next = new LlamaPromise<U>();

            whenComplete((v, err) -> dispatch(executor, next, () -> {
                if (err != null) {
                    propagateFailure(next, err, isCancelled());
                    return;
                }
                try {
                    next.complete(fn.apply(v));
                } catch (Throwable t) {
                    next.fail(t);
                }
            }));

            return next.future();
        }

        // Inline on purpose, like thenApply: fn runs on the thread that settles this future, or on the calling thread
        // when it is already settled, and only starts the inner future.
        @Override
        public <U> LlamaFuture<U> thenCompose(Function<? super T, ? extends LlamaFuture<U>> fn) {
            return thenComposeAsync(Runnable::run, fn);
        }

        @Override
        public <U> LlamaFuture<U> thenComposeAsync(Executor executor, Function<? super T, ? extends LlamaFuture<U>> fn) {
            if (executor == null) {
                throw new NullPointerException("executor");
            }
            if (fn == null) {
                throw new NullPointerException("fn");
            }
            var next = new LlamaPromise<U>();

            whenComplete((v, err) -> dispatch(executor, next, () -> {
                if (err != null) {
                    propagateFailure(next, err, isCancelled());
                    return;
                }
                try {
                    var inner = fn.apply(v);
                    inner.whenComplete((iv, ierr) -> {
                        if (ierr != null) {
                            propagateFailure(next, ierr, inner.isCancelled());
                            return;
                        }
                        next.complete(iv);
                    });
                } catch (Throwable t) {
                    next.fail(t);
                }
            }));

            return next.future();
        }

        // A failing execute is treated as a task that will not run, so the derived future fails here.
        private static void dispatch(Executor executor, LlamaPromise<?> next, Runnable task) {
            try {
                executor.execute(task);
            } catch (Throwable t) {
                next.fail(t);
            }
        }

        // The source's state decides, not the exception type: a stage that joins a cancelled future also throws
        // LlamaCancelledException, and that stage has failed rather than been cancelled.
        private static void propagateFailure(LlamaPromise<?> next, Throwable err, boolean sourceCancelled) {
            if (sourceCancelled) {
                next.cancel();
                return;
            }
            next.fail(err);
        }

        @Override
        public LlamaFuture<T> exceptionally(Function<? super Throwable, ? extends T> fn) {
            if (fn == null) {
                throw new NullPointerException("fn");
            }
            var next = new LlamaPromise<T>();

            whenComplete((v, err) -> {
                if (err == null) {
                    next.complete(v);
                    return;
                }
                try {
                    next.complete(fn.apply(err));
                } catch (Throwable t) {
                    next.fail(t);
                }
            });

            return next.future();
        }

        private record Outcome<T>(T value, Throwable error, boolean cancelled) {

            Throwable failure() {
                if (cancelled) {
                    return new LlamaCancelledException();
                }
                return error;
            }
        }
    }

    public static final class LlamaAsyncException extends RuntimeException {
        public LlamaAsyncException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class LlamaCancelledException extends RuntimeException {
        public LlamaCancelledException() { super("Future cancelled"); }
    }
}
