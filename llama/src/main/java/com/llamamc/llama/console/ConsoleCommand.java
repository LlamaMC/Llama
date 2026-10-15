package com.llamamc.llama.console;

import java.util.function.Consumer;

/**
 * A built-in console command. Runs on the console thread; the action receives the arguments after the command name.
 */
public record ConsoleCommand(String name, String description, Consumer<String[]> action) {
}
