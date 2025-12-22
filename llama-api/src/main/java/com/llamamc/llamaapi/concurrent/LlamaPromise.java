package com.llamamc.llamaapi.concurrent;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
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

        private final AtomicBoolean done = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final CountDownLatch latch = new CountDownLatch(1);
        private final CopyOnWriteArrayList<BiConsumer<? super T, ? super Throwable>> listeners = new CopyOnWriteArrayList<>();

        private volatile T value;
        private volatile Throwable error;

        boolean complete(T value) {
            if (!done.compareAndSet(false, true)) return false;
            this.value = value;
            latch.countDown();
            notifyListeners();
            return true;
        }

        boolean fail(Throwable error) {
            Objects.requireNonNull(error, "error");
            if (!done.compareAndSet(false, true)) return false;
            this.error = error;
            latch.countDown();
            notifyListeners();
            return true;
        }

        boolean cancel() {
            if (!done.compareAndSet(false, true)) return false;
            cancelled.set(true);
            latch.countDown();
            notifyListeners();
            return true;
        }

        private void notifyListeners() {
            for (var l : listeners) {
                try {
                    l.accept(value, errorOrCancel());
                } catch (Throwable ignored) {}
            }
            listeners.clear();
        }

        private Throwable errorOrCancel() {
            if (cancelled.get()) return new LlamaCancelledException();
            return error;
        }

        @Override
        public boolean isDone() {
            return done.get();
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
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
            if (cancelled.get()) throw new LlamaCancelledException();
            if (error != null) throw new LlamaAsyncException("Future failed", error);
            return value;
        }

        @Override
        public LlamaFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action) {
            Objects.requireNonNull(action, "action");
            if (isDone()) {
                action.accept(value, errorOrCancel());
                return this;
            }
            listeners.add(action);
            if (isDone()) {
                notifyListeners();
            }
            return this;
        }

        @Override
        public <U> LlamaFuture<U> thenApply(Function<? super T, ? extends U> fn) {
            return thenApplyAsync(Runnable::run, fn);
        }

        @Override
        public <U> LlamaFuture<U> thenApplyAsync(Executor executor, Function<? super T, ? extends U> fn) {
            Objects.requireNonNull(executor, "executor");
            Objects.requireNonNull(fn, "fn");
            var next = new LlamaPromise<U>();

            whenComplete((v, err) -> executor.execute(() -> {
                if (err != null) {
                    next.fail(err);
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

        @Override
        public <U> LlamaFuture<U> thenCompose(Function<? super T, ? extends LlamaFuture<U>> fn) {
            return thenComposeAsync(Runnable::run, fn);
        }

        @Override
        public <U> LlamaFuture<U> thenComposeAsync(Executor executor, Function<? super T, ? extends LlamaFuture<U>> fn) {
            Objects.requireNonNull(executor, "executor");
            Objects.requireNonNull(fn, "fn");
            var next = new LlamaPromise<U>();

            whenComplete((v, err) -> executor.execute(() -> {
                if (err != null) {
                    next.fail(err);
                    return;
                }
                try {
                    var inner = fn.apply(v);
                    inner.whenComplete((iv, ierr) -> {
                        if (ierr != null) next.fail(ierr);
                        else next.complete(iv);
                    });
                } catch (Throwable t) {
                    next.fail(t);
                }
            }));

            return next.future();
        }

        @Override
        public LlamaFuture<T> exceptionally(Function<? super Throwable, ? extends T> fn) {
            Objects.requireNonNull(fn, "fn");
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
    }

    public static final class LlamaAsyncException extends RuntimeException {
        public LlamaAsyncException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class LlamaCancelledException extends RuntimeException {
        public LlamaCancelledException() { super("Future cancelled"); }
    }
}
