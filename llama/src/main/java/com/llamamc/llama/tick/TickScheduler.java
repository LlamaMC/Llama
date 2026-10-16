package com.llamamc.llama.tick;

import com.llamamc.llamaapi.concurrent.LlamaFuture;
import com.llamamc.llamaapi.concurrent.LlamaPromise;
import com.llamamc.llamaapi.scheduler.IScheduler;
import com.llamamc.llamaapi.scheduler.ITaskHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class TickScheduler implements IScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(TickScheduler.class);
    private static final String PHASE_NAME = "scheduler";
    private static final String NOT_RUNNING = "Tick loop is not running";
    private static final int INITIAL_DELAYED_CAPACITY = 16;
    private static final Comparator<DelayedTask> DUE_ORDER = Comparator.comparingLong(DelayedTask::dueTick)
            .thenComparingLong(DelayedTask::sequence);

    private final TickLoop loop;
    private final AtomicLong nextSequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<LlamaPromise<?>> pending = ConcurrentHashMap.newKeySet();
    // An abandoned tick thread may still poll this queue while the fresh thread runs, so it must be thread-safe.
    private final PriorityBlockingQueue<DelayedTask> delayed =
            new PriorityBlockingQueue<>(INITIAL_DELAYED_CAPACITY, DUE_ORDER);
    // Every accepted delayed task, including those still waiting in TickLoop's task queue, so close() can cancel them.
    private final Set<DelayedTask> scheduled = ConcurrentHashMap.newKeySet();

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
        submit(promise, task);
        return promise.future();
    }

    @Override
    public ITaskHandle runLaterTicks(int ticks, Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        long dueTick = loop.tickCount() + Math.max(0, ticks);
        var entry = new DelayedTask(dueTick, nextSequence.getAndIncrement(), task, new AtomicBoolean());
        if (closed.get()) {
            entry.cancel();
            return entry;
        }
        // Registered before queueing, then checked again: either close() sees this entry, or this check sees closed.
        scheduled.add(entry);
        if (!loop.execute(() -> enqueueDelayed(entry))) {
            scheduled.remove(entry);
            entry.cancel();
            return entry;
        }
        if (closed.get()) {
            scheduled.remove(entry);
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

    /**
     * Shuts the scheduler down for good. Call it after the tick loop has stopped: promises still waiting for a tick
     * fail with {@link IllegalStateException}, delayed tasks are cancelled, and later calls fail or cancel at once.
     */
    public void close() {
        closed.set(true);
        pending.forEach(TickScheduler::failNotRunning);
        pending.clear();
        scheduled.forEach(DelayedTask::cancel);
        scheduled.clear();
    }

    private <T> void submit(LlamaPromise<T> promise, Callable<T> task) {
        if (closed.get()) {
            failNotRunning(promise);
            return;
        }
        // Registered before queueing: either close() sees this promise, or the check after queueing sees closed.
        pending.add(promise);
        if (!loop.execute(() -> runPending(promise, task))) {
            pending.remove(promise);
            failNotRunning(promise);
            return;
        }
        if (closed.get()) {
            pending.remove(promise);
            failNotRunning(promise);
        }
    }

    private <T> void runPending(LlamaPromise<T> promise, Callable<T> task) {
        pending.remove(promise);
        settle(promise, task);
    }

    private void enqueueDelayed(DelayedTask entry) {
        if (closed.get()) {
            scheduled.remove(entry);
            entry.cancel();
            return;
        }
        delayed.add(entry);
    }

    private void runDueTasks() {
        long now = loop.tickCount();
        DelayedTask next = delayed.peek();
        while (next != null && next.dueTick() <= now) {
            loop.fence();
            // An abandoned thread may have taken this entry since peek. Then the other thread runs it, not this one.
            if (delayed.remove(next)) {
                scheduled.remove(next);
                runDelayed(next);
            }
            next = delayed.peek();
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

    private static void failNotRunning(LlamaPromise<?> promise) {
        promise.fail(new IllegalStateException(NOT_RUNNING));
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
