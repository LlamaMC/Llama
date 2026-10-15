package com.llamamc.llama.watchdog;

import com.sun.management.GarbageCollectionNotificationInfo;

import javax.management.ListenerNotFoundException;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sums up stop-the-world GC pauses so the watchdog and plugin timings do not blame a JVM pause on server code.
 * Concurrent collector work (G1 concurrent cycles, ZGC/Shenandoah cycles) does not stop threads and is ignored.
 */
public final class GcPauseTracker implements AutoCloseable {
    private final AtomicLong totalPauseNanos = new AtomicLong();
    private final List<NotificationEmitter> emitters = new ArrayList<>();
    private final NotificationListener listener = this::onNotification;

    public static GcPauseTracker install() {
        GcPauseTracker tracker = new GcPauseTracker();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(tracker.listener, null, null);
                tracker.emitters.add(emitter);
            }
        }
        return tracker;
    }

    /**
     * Total stop-the-world pause time observed since installation.
     */
    public long totalPauseNanos() {
        return totalPauseNanos.get();
    }

    private void onNotification(Notification notification, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) return;
        GarbageCollectionNotificationInfo info =
                GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
        if (isPause(info.getGcName(), info.getGcAction())) {
            totalPauseNanos.addAndGet(info.getGcInfo().getDuration() * 1_000_000L);
        }
    }

    static boolean isPause(String gcName, String gcAction) {
        if (gcName.contains("Cycles")) return false;
        return !gcAction.toLowerCase().contains("concurrent");
    }

    @Override
    public void close() {
        for (NotificationEmitter emitter : emitters) {
            try {
                emitter.removeNotificationListener(listener);
            } catch (ListenerNotFoundException ignored) {
            }
        }
        emitters.clear();
    }
}
