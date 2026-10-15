package com.llamamc.llama.crash;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrashReportTest {

    @Test
    void writesReasonCauseSectionsAndThreadDump(@TempDir Path directory) throws Exception {
        Path file = new CrashReport("tick hung")
                .cause(new IllegalStateException("broken"))
                .section("Tick", "Current phase: world")
                .write(directory.resolve("crash-reports"));

        String content = Files.readString(file);
        assertTrue(file.getFileName().toString().startsWith("crash-"));
        assertTrue(content.contains("Reason: tick hung"));
        assertTrue(content.contains("java.lang.IllegalStateException: broken"));
        assertTrue(content.contains("-- Tick --\nCurrent phase: world"));
        assertTrue(content.contains("-- System --"));
        assertTrue(content.contains("\"main\""), "thread dump contains the test thread");
    }

    @Test
    void neverOverwritesAnExistingReport(@TempDir Path directory) throws Exception {
        CrashReport report = new CrashReport("first");
        Path first = report.write(directory);
        Path second = report.write(directory);
        assertNotEquals(first, second);
    }
}
