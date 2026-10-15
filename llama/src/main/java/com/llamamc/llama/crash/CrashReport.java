package com.llamamc.llama.crash;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plain-text report written to {@code crash-reports/} whenever the server has to restart or stop because of an error.
 */
public final class CrashReport {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss");

    private final String reason;
    private final LocalDateTime time = LocalDateTime.now();
    private final Map<String, String> sections = new LinkedHashMap<>();
    private Throwable cause;

    public CrashReport(String reason) {
        this.reason = reason;
    }

    public CrashReport cause(Throwable cause) {
        this.cause = cause;
        return this;
    }

    public CrashReport section(String title, String content) {
        sections.put(title, content);
        return this;
    }

    public String render() {
        StringBuilder builder = new StringBuilder();
        builder.append("---- Llama Crash Report ----\n")
                .append("Time: ").append(time).append('\n')
                .append("Reason: ").append(reason).append("\n\n");
        if (cause != null) {
            StringWriter trace = new StringWriter();
            cause.printStackTrace(new PrintWriter(trace));
            builder.append("-- Cause --\n").append(trace).append('\n');
        }
        for (Map.Entry<String, String> section : sections.entrySet()) {
            builder.append("-- ").append(section.getKey()).append(" --\n").append(section.getValue());
            if (!section.getValue().endsWith("\n")) builder.append('\n');
            builder.append('\n');
        }
        builder.append("-- System --\n").append(systemDetails()).append('\n')
                .append("-- Threads --\n").append(ThreadDumps.dumpAll());
        return builder.toString();
    }

    /**
     * Writes the report into {@code directory} and returns the file.
     */
    public Path write(Path directory) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("crash-" + FILE_TIME.format(time) + "-server.txt");
        for (int i = 2; Files.exists(file); i++) {
            file = directory.resolve("crash-" + FILE_TIME.format(time) + "-server-" + i + ".txt");
        }
        Files.writeString(file, render(), StandardCharsets.UTF_8);
        return file;
    }

    private static String systemDetails() {
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMb = runtime.maxMemory() / (1024 * 1024);
        Duration uptime = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());
        return "Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")\n"
                + "JVM: " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version") + "\n"
                + "JVM flags: " + String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()) + "\n"
                + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")\n"
                + "CPUs: " + runtime.availableProcessors() + "\n"
                + "Memory: " + usedMb + " MB used / " + maxMb + " MB max\n"
                + "Uptime: " + uptime.toString().substring(2).toLowerCase() + "\n";
    }
}
