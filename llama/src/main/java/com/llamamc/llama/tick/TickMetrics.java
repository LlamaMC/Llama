package com.llamamc.llama.tick;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rolling tick statistics. Written by the tick thread, read from any thread.
 */
public final class TickMetrics {
    private static final int TICK_HISTORY = 6000;
    private static final int PHASE_HISTORY = 100;
    private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

    private final long[] tickEnds = new long[TICK_HISTORY];
    private final long[] tickDurations = new long[TICK_HISTORY];
    private final Map<String, long[]> phaseDurations = new LinkedHashMap<>();
    private long recorded;
    private long firstTickEnd;
    private int phaseIndex;
    private int phaseSamples;

    synchronized void recordTick(long endNanos, long durationNanos) {
        if (recorded == 0) firstTickEnd = endNanos;
        int index = (int) (recorded % TICK_HISTORY);
        tickEnds[index] = endNanos;
        tickDurations[index] = durationNanos;
        recorded++;
        phaseIndex = (phaseIndex + 1) % PHASE_HISTORY;
        if (phaseSamples < PHASE_HISTORY) phaseSamples++;
    }

    synchronized void recordPhase(String phase, long durationNanos) {
        phaseDurations.computeIfAbsent(phase, _ -> new long[PHASE_HISTORY])[phaseIndex] = durationNanos;
    }

    public synchronized Snapshot snapshot(long nowNanos) {
        long[] lastMinute = lastDurations(1200);
        Map<String, Double> phases = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : phaseDurations.entrySet()) {
            long sum = 0;
            for (int i = 0; i < phaseSamples; i++) sum += entry.getValue()[i];
            phases.put(entry.getKey(), phaseSamples == 0 ? 0 : millis(sum) / phaseSamples);
        }
        return new Snapshot(
                tps(nowNanos, Duration.ofSeconds(5)),
                tps(nowNanos, Duration.ofMinutes(1)),
                tps(nowNanos, Duration.ofMinutes(5)),
                average(lastMinute),
                percentile(lastMinute, 0.95),
                max(lastMinute),
                Collections.unmodifiableMap(phases),
                recorded);
    }

    private double tps(long nowNanos, Duration window) {
        if (recorded == 0) return 0;
        long windowNanos = window.toNanos();
        long since = nowNanos - windowNanos;
        int available = (int) Math.min(recorded, TICK_HISTORY);
        int ticks = 0;
        for (int i = 1; i <= available; i++) {
            long end = tickEnds[(int) ((recorded - i) % TICK_HISTORY)];
            if (end <= since) break;
            ticks++;
        }
        long span = Math.min(windowNanos, nowNanos - firstTickEnd + TickLoop.TICK_NANOS);
        if (span <= 0) return 0;
        return Math.min(20.0, ticks * (double) NANOS_PER_SECOND / span);
    }

    private long[] lastDurations(int count) {
        int available = (int) Math.min(Math.min(recorded, TICK_HISTORY), count);
        long[] result = new long[available];
        for (int i = 0; i < available; i++) {
            result[i] = tickDurations[(int) ((recorded - 1 - i) % TICK_HISTORY)];
        }
        return result;
    }

    private static double average(long[] values) {
        if (values.length == 0) return 0;
        long sum = 0;
        for (long value : values) sum += value;
        return millis(sum) / values.length;
    }

    private static double percentile(long[] values, double percentile) {
        if (values.length == 0) return 0;
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return millis(sorted[Math.clamp(index, 0, sorted.length - 1)]);
    }

    private static double max(long[] values) {
        long max = 0;
        for (long value : values) max = Math.max(max, value);
        return millis(max);
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    /**
     * @param tps5s       ticks per second over the last 5 seconds
     * @param tps1m       ticks per second over the last minute
     * @param tps5m       ticks per second over the last 5 minutes
     * @param msptAverage average milliseconds per tick over the last 1200 ticks
     * @param msptP95     95th percentile milliseconds per tick over the last 1200 ticks
     * @param msptMax     slowest tick in milliseconds over the last 1200 ticks
     * @param phaseAverages average milliseconds per phase over the last 100 ticks
     * @param totalTicks  ticks completed since start
     */
    public record Snapshot(double tps5s, double tps1m, double tps5m, double msptAverage, double msptP95,
                           double msptMax, Map<String, Double> phaseAverages, long totalTicks) {
    }
}
