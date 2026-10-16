package com.llamamc.llama;

import com.llamamc.llama.shutdown.ShutdownDeadline;
import org.apache.logging.log4j.LogManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

public final class Main {
    private static final Duration HOOK_MARGIN = Duration.ofSeconds(5);
    private static final Duration HOOK_TIMEOUT = ShutdownDeadline.BUDGET.plus(HOOK_MARGIN);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(10);

    private static volatile boolean jvmShuttingDown;

    private Main() {
    }

    public static void main(String[] args) {
        Logger logger = LoggerFactory.getLogger("Llama");
        Llama llama = new Llama();

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            logger.error("Uncaught exception in thread {}", thread.getName(), error);
            if (error instanceof OutOfMemoryError) llama.restart("out of memory in thread " + thread.getName(), error);
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            jvmShuttingDown = true;
            llama.requestShutdown(ExitCode.STOP, "JVM shutdown", null, false);
            try {
                llama.awaitShutdownComplete(HOOK_TIMEOUT);
            } catch (InterruptedException ignored) {
            }
            LogManager.shutdown();
        }, "Llama-Shutdown-Hook"));

        try {
            llama.start();
        } catch (Throwable t) {
            logger.error("Server failed to start", t);
            llama.requestShutdown(ExitCode.ERROR, "startup failed", t, false);
        }
        ExitCode exitCode;
        try {
            exitCode = llama.awaitShutdown();
        } catch (InterruptedException e) {
            exitCode = ExitCode.ERROR;
        }
        if (jvmShuttingDown) return;

        int code = exitCode.code();
        Thread.ofPlatform().name("Llama-Exit-Timeout").daemon().start(() -> {
            try {
                Thread.sleep(EXIT_TIMEOUT);
            } catch (InterruptedException ignored) {
            }
            Runtime.getRuntime().halt(code);
        });
        LogManager.shutdown();
        System.exit(code);
    }
}
