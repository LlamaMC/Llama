package com.llamamc.llama.tick;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TickMetricsTest {
    private static final long MS = 1_000_000;

    @Test
    void fullSpeedReportsTwentyTps() {
        TickMetrics metrics = new TickMetrics();
        long now = 0;
        for (int i = 0; i < 2400; i++) {
            now += TickLoop.TICK_NANOS;
            metrics.recordTick(now, 2 * MS);
        }
        TickMetrics.Snapshot snapshot = metrics.snapshot(now);
        assertEquals(20.0, snapshot.tps5s(), 0.01);
        assertEquals(20.0, snapshot.tps1m(), 0.01);
        assertEquals(2.0, snapshot.msptAverage(), 0.001);
        assertEquals(2400, snapshot.totalTicks());
    }

    @Test
    void slowTicksLowerTpsAndShowInMspt() {
        TickMetrics metrics = new TickMetrics();
        long now = 0;
        for (int i = 0; i < 200; i++) {
            now += 100 * MS;
            metrics.recordTick(now, i == 199 ? 250 * MS : 100 * MS);
        }
        TickMetrics.Snapshot snapshot = metrics.snapshot(now);
        assertEquals(10.0, snapshot.tps5s(), 0.01);
        assertEquals(250.0, snapshot.msptMax(), 0.001);
        assertEquals(100.0, snapshot.msptP95(), 0.001);
    }

    @Test
    void phaseAveragesCoverRecordedTicks() {
        TickMetrics metrics = new TickMetrics();
        for (int i = 1; i <= 10; i++) {
            metrics.recordPhase("world", i * MS);
            metrics.recordTick(i * TickLoop.TICK_NANOS, i * MS);
        }
        assertEquals(5.5, metrics.snapshot(10 * TickLoop.TICK_NANOS).phaseAverages().get("world"), 0.001);
    }
}
