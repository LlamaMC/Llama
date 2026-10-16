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
    private static final Pattern WHITESPACE = Pattern.compile("\\p{javaWhitespace}+");

    private final Object lock = new Object();
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    @Override
    public void register(ICommand command) {
        if (command == null) {
            throw new IllegalArgumentException("Command must not be null");
        }
        var registration = registration(command);
        var labels = registration.labels();

        synchronized (lock) {
            for (var label : labels) {
                if (registrations.containsKey(label)) {
                    throw conflict(label);
                }
            }
            putLabels(labels, registration);
            registration.activate();
        }
    }

    @Override
    public boolean unregister(ICommand command) {
        synchronized (lock) {
            var matches = registrationsOf(command);

            for (var registration : matches) {
                registration.deactivate();
            }
            for (var registration : matches) {
                releaseLabels(registration.labels(), registration);
            }
            return !matches.isEmpty();
        }
    }

    @Override
    public boolean dispatch(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        var words = List.of(WHITESPACE.split(line.strip()));
        var label = words.getFirst().toLowerCase(Locale.ROOT);
        var registration = registrations.get(label);

        if (registration == null || !registration.active()) {
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

    // Caller holds the lock. A registration sits under several labels, so distinct() collects it once.
    private List<Registration> registrationsOf(ICommand command) {
        return registrations.values().stream()
                .filter(registration -> registration.command() == command)
                .distinct()
                .toList();
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
        var aliases = Arrays.stream(annotation.aliases()).map(alias -> label(alias, type));
        return new Registration(command, Stream.concat(Stream.of(name), aliases).toList());
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

    // dispatch can see labels while register is still publishing them, so only a fully published registration runs.
    private static final class Registration {
        private final ICommand command;
        private final List<String> labels;
        private volatile boolean active;

        Registration(ICommand command, List<String> labels) {
            this.command = command;
            this.labels = labels;
        }

        ICommand command() {
            return command;
        }

        List<String> labels() {
            return labels;
        }

        boolean active() {
            return active;
        }

        void activate() {
            active = true;
        }

        void deactivate() {
            active = false;
        }
    }

    private record DispatchContext(String label, List<String> arguments) implements ICommandContext {

        @Override
        public void reply(Component message) {
            LOGGER.info("{}", message.text());
        }
    }
}
