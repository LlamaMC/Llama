package com.llamamc.llama.shutdown;

import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * The one deadline for a whole shutdown. It starts when a shutdown is requested, and every stage waits for at most
 * the time left until it. The deadline is the sum of the {@link ShutdownWindow} budgets.
 */
public final class ShutdownDeadline {
    public static final Duration BUDGET = totalBudget();

    private final LongSupplier nanoTime;
    private final long deadlineNanos;

    /**
     * @param nanoTime the clock, normally {@code System::nanoTime}; tests inject their own
     */
    public ShutdownDeadline(LongSupplier nanoTime) {
        if (nanoTime == null) {
            throw new NullPointerException("nanoTime");
        }
        this.nanoTime = nanoTime;
        deadlineNanos = nanoTime.getAsLong() + BUDGET.toNanos();
    }

    /**
     * @return the time left until the deadline, or zero once it has passed
     */
    public Duration remaining() {
        return Duration.ofNanos(remainingNanos());
    }

    public long remainingNanos() {
        return Math.max(0, deadlineNanos - nanoTime.getAsLong());
    }

    /**
     * @return the window's budget, or the time left if that is shorter
     */
    public Duration forWindow(ShutdownWindow window) {
        if (window == null) {
            throw new NullPointerException("window");
        }
        return forStage(window.budget());
    }

    /**
     * @return the stage budget, or the time left if that is shorter
     */
    public Duration forStage(Duration stageBudget) {
        if (stageBudget == null) {
            throw new NullPointerException("stageBudget");
        }
        var left = remaining();
        return stageBudget.compareTo(left) <= 0 ? stageBudget : left;
    }

    private static Duration totalBudget() {
        var total = Duration.ZERO;
        for (var window : ShutdownWindow.values()) {
            total = total.plus(window.budget());
        }
        return total;
    }
}
