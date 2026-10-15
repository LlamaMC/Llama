package com.llamamc.llama.watchdog;

import java.time.Duration;

/**
 * @param pollInterval          how often the watchdog checks the tick
 * @param warnAfter             a tick running this long (minus GC pauses) is logged with the tick thread's stack
 * @param takeOverAfter         a tick running this long is considered hung and handed over to a fresh thread
 * @param maxAbandonedThreads   hung threads that may stay alive at once; one more means the server restarts
 * @param maxTakeovers          takeovers allowed within {@code takeoverWindow}; one more means the server restarts
 * @param takeoverWindow        window for {@code maxTakeovers}
 * @param maxCoreErrors         core errors tolerated within {@code coreErrorWindow} after a takeover
 * @param coreErrorWindow       how long after a takeover core errors count as a sign of a broken tick state
 */
public record WatchdogSettings(Duration pollInterval, Duration warnAfter, Duration takeOverAfter,
                               int maxAbandonedThreads, int maxTakeovers, Duration takeoverWindow,
                               int maxCoreErrors, Duration coreErrorWindow) {

    public static WatchdogSettings defaults() {
        return new WatchdogSettings(Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(5),
                1, 2, Duration.ofMinutes(10), 5, Duration.ofMinutes(1));
    }
}
