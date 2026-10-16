package com.llamamc.llama.command;

import com.llamamc.llamaapi.command.Command;
import com.llamamc.llamaapi.command.ICommand;
import com.llamamc.llamaapi.command.ICommandContext;
import com.llamamc.llamaapi.command.ICommandRegistry;
import com.llamamc.llamaapi.component.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class CommandRegistry implements ICommandRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandRegistry.class);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    @Override
    public void register(ICommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Command must not be null");
        }
        var registration = registration(command);
        var labels = registration.labels();

        for (var label : labels) {
            if (registrations.containsKey(label)) {
                throw conflict(label);
            }
        }
        putLabels(labels, registration);
    }

    @Override
    public boolean unregister(ICommand command) {
        var removed = false;

        for (var entry : registrations.entrySet()) {
            if (entry.getValue().command() == command && registrations.remove(entry.getKey(), entry.getValue())) {
                removed = true;
            }
        }
        return removed;
    }

    @Override
    public boolean dispatch(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        var words = List.of(WHITESPACE.split(line.strip()));
        var label = words.getFirst().toLowerCase(Locale.ROOT);
        var registration = registrations.get(label);

        if (registration == null) {
            return false;
        }
        execute(registration.command(), new DispatchContext(label, words.subList(1, words.size())));
        return true;
    }

    private void execute(ICommand command, DispatchContext context) {
        try {
            command.execute(context);
        } catch (RuntimeException error) {
            LOGGER.error("Command '{}' failed", context.label(), error);
            context.reply(Component.of("Command failed: " + error.getMessage()));
        }
    }

    private void putLabels(List<String> labels, Registration registration) {
        var added = new ArrayList<String>(labels.size());

        for (var label : labels) {
            if (registrations.putIfAbsent(label, registration) != null) {
                releaseLabels(added, registration);
                throw conflict(label);
            }
            added.add(label);
        }
    }

    // Only this registration's own entries are removed, so a label another thread has already taken stays put.
    private void releaseLabels(List<String> labels, Registration registration) {
        for (var label : labels) {
            registrations.remove(label, registration);
        }
    }

    private static IllegalStateException conflict(String label) {
        return new IllegalStateException("Command label '" + label + "' is already registered");
    }

    private static Registration registration(ICommand command) {
        var type = command.getClass();
        var annotation = type.getAnnotation(Command.class);

        if (annotation == null) {
            throw new IllegalArgumentException(type.getName() + " is missing the @Command annotation");
        }
        var name = label(annotation.name(), type);
        var aliases = Arrays.stream(annotation.aliases()).map(alias -> label(alias, type)).toList();
        return new Registration(command, name, aliases);
    }

    private static String label(String value, Class<?> type) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Blank command label on " + type.getName());
        }
        if (WHITESPACE.matcher(value).find()) {
            throw new IllegalArgumentException(
                    "Command label '%s' on %s contains whitespace".formatted(value, type.getName()));
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private record Registration(ICommand command, String name, List<String> aliases) {

        List<String> labels() {
            return Stream.concat(Stream.of(name), aliases.stream()).toList();
        }
    }

    private record DispatchContext(String label, List<String> arguments) implements ICommandContext {

        @Override
        public void reply(Component message) {
            LOGGER.info("{}", message.text());
        }
    }
}
