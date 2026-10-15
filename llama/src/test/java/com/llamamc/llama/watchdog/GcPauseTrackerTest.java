package com.llamamc.llama.watchdog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GcPauseTrackerTest {

    @Test
    void countsOnlyStopTheWorldPauses() {
        assertTrue(GcPauseTracker.isPause("G1 Young Generation", "end of minor GC"));
        assertTrue(GcPauseTracker.isPause("G1 Old Generation", "end of major GC"));
        assertTrue(GcPauseTracker.isPause("ZGC Major Pauses", "end of GC pause"));
        assertFalse(GcPauseTracker.isPause("G1 Concurrent GC", "end of concurrent GC pause"));
        assertFalse(GcPauseTracker.isPause("ZGC Major Cycles", "end of GC cycle"));
        assertFalse(GcPauseTracker.isPause("Shenandoah Cycles", "end of GC cycle"));
    }

    @Test
    void installsAndClosesOnTheRunningJvm() {
        try (GcPauseTracker tracker = GcPauseTracker.install()) {
            System.gc();
            assertTrue(tracker.totalPauseNanos() >= 0);
        }
    }
}
