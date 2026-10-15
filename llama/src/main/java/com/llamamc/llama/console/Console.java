package com.llamamc.llama.console;

import com.llamamc.llamaapi.console.IConsole;
import com.llamamc.llamaapi.console.IPrinter;
import org.jline.reader.Candidate;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * Interactive server console. Reads commands on its own daemon thread and prints all output, including
 * {@code System.out} and log messages, above the input prompt.
 */
public final class Console implements IConsole, AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Console.class);
    private static final String PROMPT = "> ";

    private final Map<String, ConsoleCommand> commands = new TreeMap<>();
    private final Runnable interruptAction;

    private Terminal terminal;
    private LineReader reader;
    private IPrinter printer;
    private Thread thread;
    private PrintStream originalOut;
    private PrintStream originalErr;
    private volatile boolean closed;

    /**
     * @param interruptAction runs when the user presses Ctrl+C in the console
     */
    public Console(Runnable interruptAction) {
        this.interruptAction = interruptAction;
        register(new ConsoleCommand("help", "Lists all console commands", _ -> printHelp()));
    }

    public void register(ConsoleCommand command) {
        commands.put(command.name(), command);
    }

    @Override
    public synchronized void start() {
        if (thread != null) throw new IllegalStateException("Console is already running");
        try {
            terminal = TerminalBuilder.builder().system(true).dumb(true).build();
        } catch (IOException e) {
            throw new IllegalStateException("Could not open a terminal", e);
        }
        reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .appName("Llama")
                .completer((_, line, candidates) -> {
                    if (line.wordIndex() != 0) return;
                    for (String name : commands.keySet()) candidates.add(new Candidate(name));
                })
                .build();
        LineReader lineReader = reader;
        printer = new Printer(lineReader::printAbove, "Llama » ");

        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(new LineOutputStream(lineReader::printAbove), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new LineOutputStream(lineReader::printAbove), true, StandardCharsets.UTF_8));

        thread = Thread.ofPlatform().name("Llama-Console").daemon().start(this::readLoop);
    }

    private void readLoop() {
        while (!closed) {
            String line;
            try {
                line = reader.readLine(PROMPT);
            } catch (UserInterruptException e) {
                interruptAction.run();
                continue;
            } catch (EndOfFileException e) {
                if (!closed) LOGGER.info("Console input closed; the server keeps running");
                return;
            } catch (Throwable t) {
                if (!closed) LOGGER.error("Console input failed; the server keeps running", t);
                return;
            }
            if (line == null) return;
            line = line.trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split("\\s+");
            handleCommand(parts[0], parts);
        }
    }

    @Override
    public void handleCommand(String cmd, String[] parts) {
        ConsoleCommand command = commands.get(cmd.toLowerCase());
        if (command == null) {
            LOGGER.info("Unknown command '{}'. Type 'help' for a list of commands.", cmd);
            return;
        }
        try {
            command.action().accept(Arrays.copyOfRange(parts, 1, parts.length));
        } catch (Throwable t) {
            LOGGER.error("Console command '{}' failed", cmd, t);
        }
    }

    private void printHelp() {
        StringBuilder help = new StringBuilder("Console commands:");
        for (ConsoleCommand command : commands.values()) {
            help.append("\n  ").append(command.name()).append(" - ").append(command.description());
        }
        LOGGER.info(help.toString());
    }

    @Override
    public void exit() {
        close();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (originalOut != null) {
            System.out.flush();
            System.err.flush();
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
        if (terminal != null) {
            try {
                terminal.close();
            } catch (IOException e) {
                LOGGER.debug("Could not close terminal", e);
            }
        }
    }

    @Override
    public IPrinter getPrinter() {
        IPrinter current = printer;
        return current != null ? current : new Printer(System.out::println, "Llama » ");
    }
}
