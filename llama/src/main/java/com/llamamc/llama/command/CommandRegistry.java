package com.llamamc.llama.command;

import com.llamamc.llamaapi.command.Command;
import com.llamamc.llamaapi.command.ICommand;
import com.llamamc.llamaapi.command.ICommandContext;
import com.llamamc.llamaapi.command.ICommandRegistry;
import com.llamamc.llamaapi.command.ICommandSender;
import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.permission.IPermissionService;
import com.llamamc.llamaapi.permission.PermissionDefault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class CommandRegistry implements ICommandRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandRegistry.class);
    private static final Pattern WHITESPACE = Pattern.compile("\\p{javaWhitespace}+");
    private static final String NODE_PREFIX = "llama.command.";
    private static final String DENIED = "You do not have permission to use this command.";

    private final Object lock = new Object();
    private final IPermissionService permissions;
    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    public CommandRegistry(IPermissionService permissions) {
        if (permissions == null) {
            throw new IllegalArgumentException("Permission service must not be null");
        }
        this.permissions = permissions;
    }

    @Override
    public void register(Object owner, Object handler) {
        if (owner == null) {
            throw new IllegalArgumentException("Command owner must not be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Command handler must not be null");
        }
        var batch = handlerRegistrations(owner, handler);

        synchronized (lock) {
            putLabels(batch);
            try {
                registerNodes(batch);
            } catch (RuntimeException error) {
                releaseLabels(batch);
                throw error;
            }
            batch.forEach(Registration::activate);
        }
    }

    @Override
    public boolean unregister(Object owner) {
        if (owner == null) {
            throw new IllegalArgumentException("Command owner must not be null");
        }
        synchronized (lock) {
            var matches = registrationsOf(owner);

            matches.forEach(Registration::deactivate);
            releaseLabels(matches);
            return !matches.isEmpty();
        }
    }

    @Override
    public boolean dispatch(ICommandSender sender, String line) {
        if (sender == null) {
            throw new IllegalArgumentException("Command sender must not be null");
        }
        if (line == null || line.isBlank()) {
            return false;
        }
        var words = List.of(WHITESPACE.split(line.strip()));
        var label = words.getFirst().toLowerCase(Locale.ROOT);
        var registration = registrations.get(label);

        if (registration == null || !registration.active()) {
            return false;
        }
        var context = new DispatchContext(label, words.subList(1, words.size()), sender);
        if (!sender.hasPermission(registration.node())) {
            context.reply(Component.of(DENIED));
            return true;
        }
        execute(registration.command(), context);
        return true;
    }

    @Override
    public List<String> commandNames() {
        synchronized (lock) {
            return registrations.values().stream()
                    .distinct()
                    .filter(Registration::active)
                    .map(Registration::name)
                    .sorted()
                    .toList();
        }
    }

    private void execute(ICommand command, ICommandContext context) {
        try {
            command.execute(context);
        } catch (RuntimeException error) {
            LOGGER.error("Command '{}' failed", context.label(), error);
            context.reply(Component.of("Command failed: " + error.getMessage()));
        }
    }

    // Caller holds the lock. Every label of the batch is published, or none is.
    private void putLabels(List<Registration> batch) {
        for (var registration : batch) {
            for (var label : registration.labels()) {
                if (registrations.putIfAbsent(label, registration) != null) {
                    releaseLabels(batch);
                    throw conflict(label);
                }
            }
        }
    }

    // Caller holds the lock. Only this batch's own entries are removed, so a label another owner holds stays put.
    private void releaseLabels(List<Registration> batch) {
        for (var registration : batch) {
            for (var label : registration.labels()) {
                registrations.remove(label, registration);
            }
        }
    }

    // Caller holds the lock. A node is registered with OP, so only users granted llama.op may run the command by default.
    private void registerNodes(List<Registration> batch) {
        for (var registration : batch) {
            permissions.register(registration.node(), PermissionDefault.OP);
        }
    }

    // Caller holds the lock. A registration sits under several labels, so distinct() collects it once.
    private List<Registration> registrationsOf(Object owner) {
        return registrations.values().stream()
                .filter(registration -> registration.owner() == owner)
                .distinct()
                .toList();
    }

    private static IllegalStateException conflict(String label) {
        return new IllegalStateException("Command label '" + label + "' is already registered");
    }

    private static List<Registration> handlerRegistrations(Object owner, Object handler) {
        if (handler instanceof ICommand command) {
            return List.of(classRegistration(owner, command));
        }
        return methodRegistrations(owner, handler);
    }

    private static Registration classRegistration(Object owner, ICommand command) {
        var type = command.getClass();
        var annotation = type.getAnnotation(Command.class);

        if (annotation == null) {
            throw new IllegalArgumentException(type.getName() + " is missing the @Command annotation");
        }
        return registration(owner, command, annotation, type.getName());
    }

    private static List<Registration> methodRegistrations(Object owner, Object handler) {
        var type = handler.getClass();
        var found = new ArrayList<Registration>();

        for (var method : commandMethods(type)) {
            var annotation = method.getAnnotation(Command.class);
            var command = MethodCommand.of(handler, method, annotation);
            found.add(registration(owner, command, annotation, origin(method)));
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException(type.getName() + " is not an ICommand and has no @Command methods");
        }
        return List.copyOf(found);
    }

    // Public methods, including inherited ones, plus non-public declared methods, so that a misplaced annotation is
    // reported instead of ignored.
    private static List<Method> commandMethods(Class<?> type) {
        var methods = new LinkedHashSet<Method>(Arrays.asList(type.getMethods()));
        methods.addAll(Arrays.asList(type.getDeclaredMethods()));
        return methods.stream()
                .filter(method -> method.isAnnotationPresent(Command.class))
                .toList();
    }

    private static Registration registration(Object owner, ICommand command, Command annotation, String origin) {
        var name = label(annotation.name(), origin);
        var aliases = Arrays.stream(annotation.aliases()).map(alias -> label(alias, origin));
        var labels = Stream.concat(Stream.of(name), aliases).toList();
        var permission = annotation.permission().strip();
        var node = permission.isEmpty() ? NODE_PREFIX + name : permission;
        return new Registration(owner, command, name, node, labels);
    }

    private static String label(String value, String origin) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("Blank command label on " + origin);
        }
        if (WHITESPACE.matcher(value).find()) {
            throw new IllegalArgumentException("Command label '%s' on %s contains whitespace".formatted(value, origin));
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String origin(Method method) {
        return method.getDeclaringClass().getName() + "." + method.getName();
    }

    // dispatch can see labels while register is still publishing them, so only a fully published registration runs.
    private static final class Registration {
        private final Object owner;
        private final ICommand command;
        private final String name;
        private final String node;
        private final List<String> labels;
        private volatile boolean active;

        Registration(Object owner, ICommand command, String name, String node, List<String> labels) {
            this.owner = owner;
            this.command = command;
            this.name = name;
            this.node = node;
            this.labels = labels;
        }

        Object owner() {
            return owner;
        }

        ICommand command() {
            return command;
        }

        String name() {
            return name;
        }

        String node() {
            return node;
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

    private record DispatchContext(String label, List<String> arguments, ICommandSender sender)
            implements ICommandContext {
    }
}
