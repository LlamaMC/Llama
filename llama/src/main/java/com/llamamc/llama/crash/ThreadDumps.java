package com.llamamc.llama.crash;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

/**
 * Formats full thread stacks. {@link ThreadInfo#toString()} cuts stacks off after 8 frames, which hides the plugin
 * frame that is usually the interesting one.
 */
public final class ThreadDumps {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private ThreadDumps() {
    }

    /**
     * @return the thread's info including locks, or {@code null} if the thread is no longer alive
     */
    public static ThreadInfo info(Thread thread) {
        ThreadInfo[] infos = THREADS.getThreadInfo(new long[]{thread.threadId()}, true, true);
        return infos.length == 0 ? null : infos[0];
    }

    public static String dump(Thread thread) {
        ThreadInfo info = info(thread);
        return info == null ? "\"" + thread.getName() + "\" (terminated)\n" : format(info);
    }

    public static String dumpAll() {
        StringBuilder builder = new StringBuilder();
        for (ThreadInfo info : THREADS.dumpAllThreads(true, true)) {
            builder.append(format(info)).append('\n');
        }
        return builder.toString();
    }

    public static String format(ThreadInfo info) {
        StringBuilder builder = new StringBuilder();
        builder.append('"').append(info.getThreadName()).append("\" #").append(info.getThreadId())
                .append(' ').append(info.getThreadState());
        if (info.getLockName() != null) builder.append(" on ").append(info.getLockName());
        if (info.getLockOwnerName() != null) {
            builder.append(" owned by \"").append(info.getLockOwnerName()).append("\" #").append(info.getLockOwnerId());
        }
        builder.append('\n');

        StackTraceElement[] stack = info.getStackTrace();
        for (int i = 0; i < stack.length; i++) {
            builder.append("\tat ").append(stack[i]).append('\n');
            if (i == 0 && info.getLockInfo() != null) {
                builder.append("\t- ").append(waitVerb(info)).append(' ').append(info.getLockInfo()).append('\n');
            }
            for (MonitorInfo monitor : info.getLockedMonitors()) {
                if (monitor.getLockedStackDepth() == i) builder.append("\t- locked ").append(monitor).append('\n');
            }
        }
        LockInfo[] synchronizers = info.getLockedSynchronizers();
        if (synchronizers.length > 0) {
            builder.append("\tLocked synchronizers:\n");
            for (LockInfo synchronizer : synchronizers) builder.append("\t- ").append(synchronizer).append('\n');
        }
        return builder.toString();
    }

    private static String waitVerb(ThreadInfo info) {
        return switch (info.getThreadState()) {
            case BLOCKED -> "blocked on";
            case WAITING, TIMED_WAITING -> "waiting on";
            default -> "on";
        };
    }
}
