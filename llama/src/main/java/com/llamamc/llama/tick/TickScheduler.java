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
    private final Set<PendingCall<?>> pending = ConcurrentHashMap.newKeySet();
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
        return enqueue(new DelayedTask(dueTick, nextSequence.getAndIncrement(), task));
    }

    @Override
    public ITaskHandle runRepeating(int periodTicks, Runnable task) {
        if (task == null) {
            throw new NullPointerException("task");
        }
        if (periodTicks < 1) {
            throw new IllegalArgumentException("periodTicks must be at least 1");
        }
        long dueTick = loop.tickCount() + periodTicks;
        return enqueue(new DelayedTask(dueTick, nextSequence.getAndIncrement(), periodTicks, task));
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
        pending.forEach(TickScheduler::failIfClaimed);
        pending.clear();
        scheduled.forEach(DelayedTask::cancel);
        scheduled.clear();
    }

    private <T> void submit(LlamaPromise<T> promise, Callable<T> task) {
        if (closed.get()) {
            failNotRunning(promise);
            return;
        }
        var call = new PendingCall<>(promise);
        // Registered before queueing: either close() sees this call, or the check after queueing sees closed.
        pending.add(call);
        if (!loop.execute(() -> runPending(call, task))) {
            pending.remove(call);
            failIfClaimed(call);
            return;
        }
        if (closed.get()) {
            pending.remove(call);
            failIfClaimed(call);
        }
    }

    // The claim picks one winner: the loop runs the task, or close() or a rejection fails it, never both.
    private <T> void runPending(PendingCall<T> call, Callable<T> task) {
        var claimed = call.claim();
        pending.remove(call);
        if (!claimed) {
            return;
        }
        settle(call.promise(), task);
    }

    private ITaskHandle enqueue(DelayedTask entry) {
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
        if (entry.repeating()) {
            reschedule(entry);
        }
    }

    // Reached only after a run that completed or failed with a logged error. An unwound run never gets here.
    private void reschedule(DelayedTask entry) {
        if (entry.isCancelled()) {
            return;
        }
        // runDueTasks took the entry out of scheduled before running it, so close() cannot cancel it from there.
        if (closed.get()) {
            entry.cancel();
            return;
        }
        entry.rescheduleFrom(loop.tickCount());
        scheduled.add(entry);
        delayed.add(entry);
        // close() may have run between the check above and the add, so it is checked again.
        if (closed.get()) {
            scheduled.remove(entry);
            entry.cancel();
        }
    }

    private static void failNotRunning(LlamaPromise<?> promise) {
        promise.fail(new IllegalStateException(NOT_RUNNING));
    }

    private static void failIfClaimed(PendingCall<?> call) {
        if (!call.claim()) {
            return;
        }
        failNotRunning(call.promise());
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

    // A promise waiting in the task queue. Only the first party to claim it may settle it.
    private static final class PendingCall<T> {
        private final LlamaPromise<T> promise;
        private final AtomicBoolean claimed = new AtomicBoolean();

        PendingCall(LlamaPromise<T> promise) {
            this.promise = promise;
        }

        LlamaPromise<T> promise() {
            return promise;
        }

        boolean claim() {
            return claimed.compareAndSet(false, true);
        }
    }

    // Identity equality on purpose: a user Runnable with a changing hashCode must not lose its entry from the sets.
    private static final class DelayedTask implements ITaskHandle {
        private long dueTick;
        private final long sequence;
        private final long period;
        private final Runnable task;
        private final AtomicBoolean cancelled = new AtomicBoolean();

        DelayedTask(long dueTick, long sequence, Runnable task) {
            this(dueTick, sequence, 0, task);
        }

        DelayedTask(long dueTick, long sequence, long period, Runnable task) {
            this.dueTick = dueTick;
            this.sequence = sequence;
            this.period = period;
            this.task = task;
        }

        long dueTick() {
            return dueTick;
        }

        long sequence() {
            return sequence;
        }

        boolean repeating() {
            return period > 0;
        }

        // Only called while the entry is out of the delayed queue, because the queue is ordered by dueTick.
        void rescheduleFrom(long tick) {
            dueTick = tick + period;
        }

        Runnable task() {
            return task;
        }

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
