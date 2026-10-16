package com.llamamc.llama.shutdown;

import java.time.Duration;

/**
 * The four windows of a shutdown from docs/ARCHITECTURE.md section 7. A window's budget is the longest that each stage
 * assigned to it may wait, capped by the time left until the deadline. The budgets add up to the deadline.
 */
public enum ShutdownWindow {
    /** 0 to 5 s: stop intake, disconnect players and fire quit events. */
    INTAKE(Duration.ofSeconds(5)),
    /** 5 to 15 s: disable plugins, stop their tasks and freeze world state. */
    FREEZE(Duration.ofSeconds(10)),
    /** 15 to 50 s: flush and persist chunks, players and world data. */
    PERSIST(Duration.ofSeconds(35)),
    /** 50 to 60 s: terminate executors, Netty and remaining resources. */
    TEARDOWN(Duration.ofSeconds(10));

    private final Duration budget;

    ShutdownWindow(Duration budget) {
        this.budget = budget;
    }

    public Duration budget() {
        return budget;
    }
}
