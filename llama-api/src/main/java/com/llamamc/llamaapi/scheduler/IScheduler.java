package com.llamamc.llamaapi.scheduler;

import com.llamamc.llamaapi.concurrent.LlamaFuture;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

public interface IScheduler {

    boolean isTickThread();

    // Never runs inline, even when called from the tick thread. Fails at once if the loop is stopped.
    LlamaFuture<Void> runOnTick(Runnable task);

    <T> LlamaFuture<T> callOnTick(Callable<T> task);

    // Ticks of zero or less behave like runOnTick.
    ITaskHandle runLaterTicks(int ticks, Runnable task);

    // Runs on a virtual thread, so it never blocks or runs on the tick thread.
    <T> LlamaFuture<T> supplyAsync(Supplier<T> task);

}
