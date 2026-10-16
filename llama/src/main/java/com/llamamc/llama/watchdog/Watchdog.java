package com.llamamc.llama.watchdog;

import com.llamamc.llama.crash.ThreadDumps;
import com.llamamc.llama.tick.TickLoop;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ThreadInfo;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Watches the tick loop from its own thread.
 * <p>
 * A tick that runs longer than {@link WatchdogSettings#warnAfter()} is logged. A tick that runs longer than
 * {@link WatchdogSettings#takeOverAfter()} is considered hung: if the server state still looks recoverable, the hung
 * thread is abandoned and a fresh thread keeps ticking; otherwise the {@link RestartHandler} is asked to restart the
 * server. GC pauses are subtracted from the tick time so a long collection is never mistaken for a hang.
 */
public final class Watchdog {
    private static final Logger LOGGER = LoggerFactory.getLogger(Watchdog.class);

    private final TickLoop loop;
    private final LongSupplier gcPauseNanos;
    private final WatchdogSettings settings;
    private final RestartHandler restartHandler;
    private final AtomicBoolean restartRequested = new AtomicBoolean();
    private final Deque<Long> takeovers = new ArrayDeque<>();
    private final Deque<Long> coreErrors = new ArrayDeque<>();

    private volatile Thread thread;
    private volatile boolean running;
    private long warnedTick;
    private long handledTick;
    private long lastTakeover;

    public Watchdog(TickLoop loop, LongSupplier gcPauseNanos, WatchdogSettings settings, RestartHandler restartHandler) {
        this.loop = loop;
        this.gcPauseNanos = gcPauseNanos;
        this.settings = settings;
        this.restartHandler = restartHandler;
    }

    public void start() {
        running = true;
        thread = Thread.ofPlatform().name("Llama-Watchdog").daemon().priority(Thread.MAX_PRIORITY).start(this::run);
    }

    public void stop() {
        stop(settings.pollInterval().multipliedBy(10));
    }

    /**
     * @return {@code false} if the watchdog thread was still alive when the timeout ran out
     */
    public boolean stop(Duration timeout) {
        running = false;
        Thread current = thread;
        if (current == null || current == Thread.currentThread()) {
            return true;
        }
        current.interrupt();
        try {
            return current.join(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Reports an error thrown by core server code on the tick thread. Many of them shortly after a takeover mean the
     * half-finished tick left the server in a broken state.
     */
    public synchronized void reportCoreError() {
        if (lastTakeover == 0) return;
        long now = System.nanoTime();
        if (now - lastTakeover > settings.coreErrorWindow().toNanos()) return;
        coreErrors.addLast(now);
        if (coreErrors.size() > settings.maxCoreErrors()) {
            requestRestart("the server keeps failing after a hung tick was taken over (" + coreErrors.size()
                    + " core errors within " + settings.coreErrorWindow().toSeconds() + " s)");
        }
    }

    private void run() {
        while (running) {
            try {
                Thread.sleep(settings.pollInterval());
            } catch (InterruptedException e) {
                if (!running) return;
            }
            try {
                check();
            } catch (Throwable t) {
                LOGGER.error("Watchdog check failed", t);
            }
        }
    }

    synchronized void check() {
        long tickStart = loop.tickStartNanos();
        if (tickStart == 0 || !loop.isRunning()) return;
        long gcPause = gcPauseNanos.getAsLong() - loop.tickStartGcPauseNanos();
        long stall = System.nanoTime() - tickStart - Math.max(0, gcPause);
        Thread tickThread = loop.tickThread();

        if (stall >= settings.warnAfter().toNanos() && warnedTick != tickStart) {
            warnedTick = tickStart;
            LOGGER.warn("Tick is taking {} ms (phase '{}'). Tick thread stack:\n{}",
                    stall / 1_000_000, loop.currentPhase(), ThreadDumps.dump(tickThread));
        }
        if (stall >= settings.takeOverAfter().toNanos() && handledTick != tickStart) {
            handledTick = tickStart;
            handleHang(tickThread, stall);
        }
    }

    private void handleHang(Thread hung, long stallNanos) {
        String phase = loop.currentPhase();
        String stack = ThreadDumps.dump(hung);
        String unrecoverable = unrecoverableReason(hung);
        if (unrecoverable != null) {
            LOGGER.error("Tick thread {} hung for {} ms in phase '{}' and the server cannot recover: {}. Stack:\n{}",
                    hung.getName(), stallNanos / 1_000_000, phase, unrecoverable, stack);
            requestRestart(unrecoverable);
            return;
        }
        long now = System.nanoTime();
        takeovers.addLast(now);
        lastTakeover = now;
        coreErrors.clear();
        Thread abandoned = loop.takeOver();
        if (abandoned == null) return;
        LOGGER.error("Tick thread {} hung for {} ms in phase '{}'. It was abandoned and {} continues ticking. "
                        + "The hung thread keeps one CPU core busy until it returns; a restart is recommended "
                        + "when convenient. Stack:\n{}",
                abandoned.getName(), stallNanos / 1_000_000, phase, loop.tickThread().getName(), stack);
    }

    private String unrecoverableReason(Thread hung) {
        ThreadInfo info = ThreadDumps.info(hung);
        if (info != null && info.getLockOwnerId() != -1 && loop.isAbandoned(info.getLockOwnerId())) {
            return "the tick thread is blocked on a lock held by the abandoned thread '" + info.getLockOwnerName() + "'";
        }
        List<Thread> alive = loop.abandonedThreads();
        if (alive.size() + 1 > settings.maxAbandonedThreads()) {
            return "too many hung tick threads (" + (alive.size() + 1) + ")";
        }
        long now = System.nanoTime();
        while (!takeovers.isEmpty() && now - takeovers.peekFirst() > settings.takeoverWindow().toNanos()) {
            takeovers.removeFirst();
        }
        if (takeovers.size() + 1 > settings.maxTakeovers()) {
            return "the tick hung " + (takeovers.size() + 1) + " times within "
                    + settings.takeoverWindow().toMinutes() + " min";
        }
        return null;
    }

    private void requestRestart(String reason) {
        if (restartRequested.compareAndSet(false, true)) {
            restartHandler.restart(reason);
        }
    }

    @FunctionalInterface
    public interface RestartHandler {
        void restart(String reason);
    }
}
