package com.llamamc.llama.shutdown;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(10)
class ShutdownDeadlineTest {
    private final AtomicLong clock = new AtomicLong();
    private final ShutdownDeadline deadline = new ShutdownDeadline(clock::get);

    @Test
    void remainingDecreasesWithTheInjectedClock() {
        assertEquals(ShutdownDeadline.BUDGET, deadline.remaining());

        clock.set(Duration.ofSeconds(20).toNanos());
        assertEquals(Duration.ofSeconds(40), deadline.remaining());
    }

    @Test
    void stageGetsItsBudgetWhenTimeIsPlentiful() {
        assertEquals(Duration.ofSeconds(10), deadline.forStage(Duration.ofSeconds(10)));
    }

    @Test
    void stageGetsTheRemainingTimeWhenItIsShort() {
        clock.set(Duration.ofSeconds(55).toNanos());
        assertEquals(Duration.ofSeconds(5), deadline.forStage(Duration.ofSeconds(10)));
    }

    @Test
    void expiredDeadlineGivesZero() {
        clock.set(Duration.ofSeconds(61).toNanos());
        assertEquals(Duration.ZERO, deadline.remaining());
        assertEquals(Duration.ZERO, deadline.forStage(Duration.ofSeconds(10)));
    }
}
