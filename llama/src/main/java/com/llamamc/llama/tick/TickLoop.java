package com.llamamc.llama.tick;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;

/**
 * Runs the server tick at a fixed rate of 20 ticks per second.
 * <p>
 * The loop is not bound to a specific thread: which thread currently ticks is a replaceable owner. When the
 * watchdog detects a hung tick it calls {@link #takeOver()}, which abandons the hung thread and lets a fresh
 * thread continue ticking. An abandoned thread is fenced: as soon as it returns into loop or server code
 * ({@link #fence()}), it is unwound with an {@link AbandonedThreadError} and can no longer change server state.
 */
public final class TickLoop {
    public static final long TICK_NANOS = Duration.ofMillis(50).toNanos();
    public static final String TASKS_PHASE = "tasks";

    private static final Logger LOGGER = LoggerFactory.getLogger(TickLoop.class);
    private static final long MAX_CATCH_UP_NANOS = Duration.ofSeconds(2).toNanos();
    private static final long BEHIND_WARNING_INTERVAL_NANOS = Duration.ofSeconds(15).toNanos();
    private static final long SPIN_NANOS = 100_000;

    private final List<TickPhase> phases = new CopyOnWriteArrayList<>();
    private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private final Set<Thread> abandoned = ConcurrentHashMap.newKeySet();
    private final TickMetrics metrics = new TickMetrics();
    private final LongSupplier gcPauseNanos;
    private final ErrorHandler errorHandler;
    private final Object ownerLock = new Object();

    private volatile boolean running;
    private volatile boolean stopped;
    private volatile Thread tickThread;
    private volatile long tickStartNanos;
    private volatile long tickStartGcPauseNanos;
    private volatile String currentPhase;
    private volatile long tickCount;
    private int generation;
    private long lastBehindWarning;

    /**
     * @param gcPauseNanos total stop-the-world GC pause time so far, used to tell GC pauses apart from hangs
     * @param errorHandler receives every throwable that escapes a phase or task
     */
    public TickLoop(LongSupplier gcPauseNanos, ErrorHandler errorHandler) {
        this.gcPauseNanos = gcPauseNanos;
        this.errorHandler = errorHandler;
        phases.add(new TickPhase(TASKS_PHASE, this::runTasks));
    }

    /**
     * Adds a phase that runs once per tick, after all previously added phases.
     */
    public void addPhase(String name, Runnable action) {
        phases.add(new TickPhase(name, action));
    }

    /**
     * Queues a task to run on the tick thread at the start of the next tick. Safe to call from any thread. Tasks may
     * be queued before {@link #start()}.
     *
     * @return {@code false} if the loop has been stopped, in which case the task is not queued
     */
    public boolean execute(Runnable task) {
        if (stopped) {
            return false;
        }
        tasks.add(task);
        return true;
    }

    public void start() {
        synchronized (ownerLock) {
            if (running) throw new IllegalStateException("Tick loop is already running");
            running = true;
            stopped = false;
            Thread thread = newTickThread();
            tickThread = thread;
            thread.start();
        }
    }

    /**
     * Stops the loop after the current tick and waits for the tick thread to finish.
     *
     * @return {@code false} if the tick thread did not finish within the timeout (for example because it hangs)
     */
    public boolean stop(Duration timeout) {
        Thread thread;
        synchronized (ownerLock) {
            running = false;
            stopped = true;
            thread = tickThread;
        }
        if (thread == null || thread == Thread.currentThread()) return true;
        LockSupport.unpark(thread);
        try {
            thread.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    /**
     * Abandons the current tick thread and continues ticking on a fresh thread.
     *
     * @return the abandoned thread, or {@code null} if the loop is not running
     */
    public Thread takeOver() {
        synchronized (ownerLock) {
            Thread old = tickThread;
            if (!running || old == null) return null;
            abandoned.add(old);
            old.setPriority(Thread.MIN_PRIORITY);
            old.interrupt();
            Thread fresh = newTickThread();
            tickThread = fresh;
            tickStartNanos = 0;
            currentPhase = null;
            fresh.start();
            return old;
        }
    }

    /**
     * Unwinds the calling thread if it is an abandoned tick thread. Server code that changes state calls this at its
     * entry points so a thread that was given up can never touch the server again.
     */
    public void fence() {
        if (!abandoned.isEmpty() && abandoned.contains(Thread.currentThread())) {
            throw new AbandonedThreadError();
        }
    }

    public boolean isTickThread() {
        return Thread.currentThread() == tickThread;
    }

    public boolean isRunning() {
        return running;
    }

    public Thread tickThread() {
        return tickThread;
    }

    /**
     * Abandoned tick threads that are still alive.
     */
    public List<Thread> abandonedThreads() {
        abandoned.removeIf(thread -> !thread.isAlive());
        return List.copyOf(abandoned);
    }

    public boolean isAbandoned(long threadId) {
        for (Thread thread : abandoned) {
            if (thread.threadId() == threadId) return true;
        }
        return false;
    }

    /**
     * {@link System#nanoTime()} at which the running tick started, or {@code 0} while the loop waits between ticks.
     */
    public long tickStartNanos() {
        return tickStartNanos;
    }

    /**
     * Value of the GC pause counter when the running tick started.
     */
    public long tickStartGcPauseNanos() {
        return tickStartGcPauseNanos;
    }

    public String currentPhase() {
        return currentPhase;
    }

    public long tickCount() {
        return tickCount;
    }

    public TickMetrics metrics() {
        return metrics;
    }

    private Thread newTickThread() {
        generation++;
        String name = generation == 1 ? "Llama-Main" : "Llama-Main-" + generation;
        return Thread.ofPlatform().name(name).priority(Thread.NORM_PRIORITY + 1).unstarted(this::run);
    }

    private void run() {
        Thread self = Thread.currentThread();
        try {
            long nextTick = System.nanoTime();
            while (running && tickThread == self) {
                long behind = System.nanoTime() - nextTick;
                if (behind > MAX_CATCH_UP_NANOS) {
                    warnBehind(behind);
                    nextTick = System.nanoTime();
                }
                tick(self);
                nextTick += TICK_NANOS;
                waitUntil(nextTick, self);
            }
        } catch (AbandonedThreadError e) {
            LOGGER.warn("Abandoned tick thread {} returned and was terminated", self.getName());
        } finally {
            abandoned.remove(self);
        }
    }

    private void tick(Thread self) {
        long start = System.nanoTime();
        tickStartGcPauseNanos = gcPauseNanos.getAsLong();
        tickStartNanos = start;
        try {
            for (TickPhase phase : phases) {
                fence();
                currentPhase = phase.name();
                long phaseStart = System.nanoTime();
                try {
                    phase.action().run();
                } catch (AbandonedThreadError e) {
                    throw e;
                } catch (Throwable t) {
                    fence();
                    errorHandler.onError(phase.name(), t);
                }
                metrics.recordPhase(phase.name(), System.nanoTime() - phaseStart);
            }
            fence();
        } finally {
            synchronized (ownerLock) {
                if (tickThread == self) {
                    long end = System.nanoTime();
                    tickStartNanos = 0;
                    currentPhase = null;
                    tickCount++;
                    metrics.recordTick(end, end - start);
                }
            }
        }
    }

    private void runTasks() {
        int pending = tasks.size();
        for (int i = 0; i < pending; i++) {
            // Peek, fence, then remove: a task leaves the queue only after the fence passes, so an abandoned thread
            // never takes a task that the fresh thread should run.
            Runnable task = tasks.peek();
            if (task == null) {
                return;
            }
            fence();
            if (tasks.remove(task)) {
                runTask(task);
            }
        }
    }

    private void runTask(Runnable task) {
        try {
            task.run();
        } catch (AbandonedThreadError e) {
            throw e;
        } catch (Throwable t) {
            fence();
            errorHandler.onError(TASKS_PHASE, t);
        }
    }

    private void waitUntil(long deadline, Thread self) {
        long remaining;
        while ((remaining = deadline - System.nanoTime()) > 0 && running && tickThread == self) {
            if (remaining > SPIN_NANOS) {
                LockSupport.parkNanos(this, remaining - SPIN_NANOS);
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private void warnBehind(long behindNanos) {
        long now = System.nanoTime();
        if (lastBehindWarning != 0 && now - lastBehindWarning < BEHIND_WARNING_INTERVAL_NANOS) return;
        lastBehindWarning = now;
        LOGGER.warn("Can't keep up! Running {} ms ({} ticks) behind, skipping ahead",
                behindNanos / 1_000_000, behindNanos / TICK_NANOS);
    }

    @FunctionalInterface
    public interface ErrorHandler {
        void onError(String phase, Throwable error);
    }
}
