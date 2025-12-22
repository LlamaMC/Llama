package com.llamamc.llamaapi.concurrent;

import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;

public interface LlamaFuture<T> {

    boolean isDone();
    boolean isCancelled();

    T join();
    T await() throws InterruptedException;

    LlamaFuture<T> whenComplete(BiConsumer<? super T, ? super Throwable> action);

    <U> LlamaFuture<U> thenApply(Function<? super T, ? extends U> fn);
    <U> LlamaFuture<U> thenApplyAsync(Executor executor, Function<? super T, ? extends U> fn);

    <U> LlamaFuture<U> thenCompose(Function<? super T, ? extends LlamaFuture<U>> fn);
    <U> LlamaFuture<U> thenComposeAsync(Executor executor, Function<? super T, ? extends LlamaFuture<U>> fn);

    LlamaFuture<T> exceptionally(Function<? super Throwable, ? extends T> fn);
}
