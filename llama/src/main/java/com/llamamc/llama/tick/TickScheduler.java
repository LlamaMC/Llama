package com.llamamc.llama.tick;

import com.llamamc.llamaapi.concurrent.LlamaFuture;
import com.llamamc.llamaapi.concurrent.LlamaPromise;
import com.llamamc.llamaapi.scheduler.IScheduler;
import com.llamamc.llamaapi.scheduler.ITaskHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class TickScheduler implements IScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickScheduler.class);
    private static final String PHASE_NAME = "scheduler";
    private static final String NOT_RUNNING = "Tick loop is not running";
    private static final Comparator<DelayedTask> DUE_ORDER = Comparator.comparingLong(DelayedTask::dueTick)
            .thenComparingLong(DelayedTask::sequence);

    private final TickLoop loop;
    private final AtomicLong nextSequence = new AtomicLong();
    // Only the tick thread touches this queue. Entries arrive through queued tasks, so no lock is needed.
    private final PriorityQueue<DelayedTask> delayed = new PriorityQueue<>(DUE_ORDER);

    public TickScheduler(TickLoop loop) {
        this.loop = loop;
        // TickLoop cannot remove a phase, so this phase lives as long as the loop that owns it.
        loop.addPhase(PHASE_NAME, this::runDueTasks);
    }

    @Override
    public boolean isTickThread() {
        return loop.isTickThread();
    }

    @Override
    public LlamaFuture<Void> runOnTick(Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        return callOnTick(() -> {
            task.run();
            return null;
        });
    }

    @Override
    public <T> LlamaFuture<T> callOnTick(Callable<T> task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        var promise = new LlamaPromise<T>();
        if (!loop.execute(() -> settle(promise, task))) {
            promise.fail(new IllegalStateException(NOT_RUNNING));
        }
        return promise.future();
    }

    @Override
    public ITaskHandle runLaterTicks(int ticks, Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        long dueTick = loop.tickCount() + Math.max(0, ticks);
        var entry = new DelayedTask(dueTick, nextSequence.getAndIncrement(), task, new AtomicBoolean());
        if (!loop.execute(() -> delayed.add(entry))) {
            entry.cancel();
        }
        return entry;
    }

    @Override
    public <T> LlamaFuture<T> supplyAsync(Supplier<T> task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        var promise = new LlamaPromise<T>();
        Thread.startVirtualThread(() -> settle(promise, task::get));
        return promise.future();
    }

    private void runDueTasks() {
        long now = loop.tickCount();
        while (!delayed.isEmpty() && delayed.peek().dueTick() <= now) {
            loop.fence();
            runDelayed(delayed.poll());
        }
    }

    private void runDelayed(DelayedTask entry) {
        if (entry.isCancelled()) {
            return;
        }
        try {
            entry.task().run();
        } catch (AbandonedThreadError e) {
            throw e;
        } catch (Throwable t) {
            // An abandoned thread must not log or continue after its task fails.
            loop.fence();
            LOGGER.error("Scheduled task failed", t);
        }
    }

    // A throwing task still settles its future, so no caller waits forever on it.
    private static <T> void settle(LlamaPromise<T> promise, Callable<T> task) {
        try {
            promise.complete(task.call());
        } catch (AbandonedThreadError e) {
            promise.fail(e);
            throw e;
        } catch (Throwable t) {
            promise.fail(t);
        }
    }

    private record DelayedTask(long dueTick, long sequence, Runnable task, AtomicBoolean cancelled) implements ITaskHandle {

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
