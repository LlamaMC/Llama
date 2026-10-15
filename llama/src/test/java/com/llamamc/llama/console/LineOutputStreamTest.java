package com.llamamc.llama.console;

import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LineOutputStreamTest {

    @Test
    void emitsCompleteLinesOnly() {
        List<String> lines = new ArrayList<>();
        PrintStream out = new PrintStream(new LineOutputStream(lines::add), false, StandardCharsets.UTF_8);
        out.print("Hallo ");
        out.print("Welt\r\nÄpfel §a\nrest");
        out.flush();
        assertEquals(List.of("Hallo Welt", "Äpfel §a", "rest"), lines);
    }
}
