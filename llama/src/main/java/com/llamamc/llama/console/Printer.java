package com.llamamc.llama.console;

import com.llamamc.llamaapi.console.IPrinter;

import java.util.function.Consumer;

/**
 * Prints whole lines above the console prompt. Text printed without a line break is kept until the line is complete.
 */
public final class Printer implements IPrinter {
    private final Consumer<String> sink;
    private final String prefix;
    private final StringBuilder pending = new StringBuilder();

    public Printer(Consumer<String> sink, String prefix) {
        this.sink = sink;
        this.prefix = prefix;
    }

    @Override
    public void print(String message) {
        this.println(message, false, false);
    }

    @Override
    public void print(String message, boolean usePrefix) {
        this.println(message, usePrefix, false);
    }

    @Override
    public void println(String message) {
        this.println(message, false, true);
    }

    @Override
    public void println(String message, boolean usePrefix) {
        this.println(message, usePrefix, true);
    }

    @Override
    public synchronized void println(String message, boolean usePrefix, boolean newLine) {
        pending.append(usePrefix ? prefix : "").append(message);
        if (newLine) {
            sink.accept(pending.toString());
            pending.setLength(0);
        }
    }
}
