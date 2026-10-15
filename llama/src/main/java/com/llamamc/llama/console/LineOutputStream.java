package com.llamamc.llama.console;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Collects bytes into lines and hands every complete line to a consumer. Used to route {@code System.out} through
 * the console so output appears above the input prompt instead of inside it.
 */
final class LineOutputStream extends OutputStream {
    private final Consumer<String> sink;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

    LineOutputStream(Consumer<String> sink) {
        this.sink = sink;
    }

    @Override
    public synchronized void write(int b) {
        if (b == '\n') {
            emit();
        } else {
            buffer.write(b);
        }
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) {
        int start = offset;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            if (bytes[i] == '\n') {
                buffer.write(bytes, start, i - start);
                emit();
                start = i + 1;
            }
        }
        buffer.write(bytes, start, end - start);
    }

    @Override
    public synchronized void flush() {
        if (buffer.size() > 0) emit();
    }

    private void emit() {
        String line = buffer.toString(StandardCharsets.UTF_8);
        buffer.reset();
        if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
        sink.accept(line);
    }
}
