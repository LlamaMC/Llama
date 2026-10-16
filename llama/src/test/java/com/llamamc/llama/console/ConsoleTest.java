package com.llamamc.llama.console;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ConsoleTest {

    @Test
    void unknownCommandIsPassedToFallbackAsFullLine() {
        var console = new Console(() -> { });
        var lines = new ArrayList<String>();
        console.setFallback(line -> {
            lines.add(line);
            return true;
        });

        console.handleCommand("Greet", new String[] {"Greet", "Alice", "Bob"});

        assertEquals(List.of("Greet Alice Bob"), lines);
    }

    @Test
    void consoleCommandDoesNotReachFallback() {
        var console = new Console(() -> { });
        var arguments = new ArrayList<String>();
        var lines = new ArrayList<String>();
        console.register(new ConsoleCommand("stop", "Stops the server", args -> arguments.addAll(List.of(args))));
        console.setFallback(line -> {
            lines.add(line);
            return true;
        });

        console.handleCommand("stop", new String[] {"stop", "now"});

        assertEquals(List.of("now"), arguments);
        assertEquals(List.of(), lines);
    }

    @Test
    void unknownCommandWithoutFallbackDoesNotThrow() {
        var console = new Console(() -> { });

        assertDoesNotThrow(() -> console.handleCommand("nothing", new String[] {"nothing"}));
    }
}
