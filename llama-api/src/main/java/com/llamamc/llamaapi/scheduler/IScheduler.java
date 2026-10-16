package com.llamamc.llamaapi.scheduler;

import com.llamamc.llamaapi.concurrent.LlamaFuture;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

public interface IScheduler {

    boolean isTickThread();

    // Never runs inline, even when called from the tick thread. Fails at once if the loop is stopped.
    // Do not call join() on the returned future from the tick thread: the task can only run on that thread.
    LlamaFuture<Void> runOnTick(Runnable task);

    // Do not call join() on the returned future from the tick thread: the task can only run on that thread.
    <T> LlamaFuture<T> callOnTick(Callable<T> task);

    // Ticks of zero or less behave like runOnTick.
    ITaskHandle runLaterTicks(int ticks, Runnable task);

    /**
     * Runs the task every {@code periodTicks} ticks, starting {@code periodTicks} ticks from now. Cancel the handle to
     * stop it. A period below 1 throws {@link IllegalArgumentException}. An exception from the task is logged, and the
     * next run is still scheduled.
     */
    ITaskHandle runRepeating(int periodTicks, Runnable task);

    // Runs on a virtual thread, so it never blocks or runs on the tick thread.
    <T> LlamaFuture<T> supplyAsync(Supplier<T> task);

}
