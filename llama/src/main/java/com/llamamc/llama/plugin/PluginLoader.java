package com.llamamc.llama.plugin;

import com.llamamc.llamaapi.command.ICommandRegistry;
import com.llamamc.llamaapi.event.IEventBus;
import com.llamamc.llamaapi.permission.IPermissionService;
import com.llamamc.llamaapi.plugin.IPlugin;
import com.llamamc.llamaapi.scheduler.IScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarFile;

/**
 * Loads the plugin jars of one directory in dependency order, and disables them at shutdown. Each jar gets its own
 * class loader whose parent is the server class loader, so a plugin shares the API classes and cannot replace them.
 */
public final class PluginLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginLoader.class);
    private static final String JAR_SUFFIX = ".jar";
    private static final String STOPPED = "server stopped";

    private final Path directory;
    private final ClassLoader parent;
    private final IEventBus events;
    private final ICommandRegistry commands;
    private final PluginInjector injector;
    private final List<PluginEntry> entries = new ArrayList<>();
    private final List<PluginEntry> enabled = new ArrayList<>();

    public PluginLoader(Path directory, ClassLoader parent, IScheduler scheduler, IEventBus events,
                        ICommandRegistry commands, IPermissionService permissions) {
        requireArgument(directory, "directory");
        requireArgument(parent, "parent");
        requireArgument(scheduler, "scheduler");
        requireArgument(events, "events");
        requireArgument(commands, "commands");
        requireArgument(permissions, "permissions");
        this.directory = directory;
        this.parent = parent;
        this.events = events;
        this.commands = commands;
        this.injector = new PluginInjector(Map.<Class<?>, Object>of(
                IScheduler.class, scheduler,
                IPermissionService.class, permissions));
    }

    /**
     * Reads every jar and resolves the dependencies before anything loads. Then each plugin is loaded and enabled in
     * turn, so a service published by a plugin is available to the plugins after it.
     */
    public synchronized void loadAll() {
        var candidates = new ArrayList<PluginCandidate>();
        for (var jar : listJars()) {
            var entry = read(jar);
            entries.add(entry);
            if (entry.descriptor() != null) {
                candidates.add(new PluginCandidate(jar, entry.descriptor()));
            }
        }
        var resolution = PluginDependencyResolver.resolve(candidates);
        for (var rejection : resolution.rejected()) {
            fail(entryOf(rejection.candidate()), rejection.reason(), null);
        }
        for (var candidate : resolution.order()) {
            load(entryOf(candidate));
        }
    }

    /**
     * Disables the enabled plugins in reverse load order. A second call does nothing. The plugin's handlers and commands
     * are removed after its disable(), in a finally block, so they go even when disable() fails with an error.
     */
    public synchronized void disableAll() {
        var reversed = new ArrayList<>(enabled);
        Collections.reverse(reversed);
        for (var entry : reversed) {
            try {
                runDisable(entry);
            } finally {
                release(entry);
            }
            entry.markDisabled(STOPPED);
            LOGGER.info("Disabled plugin {}", entry.label());
        }
        enabled.clear();
    }

    public synchronized List<PluginStatus> statuses() {
        return entries.stream()
                .map(entry -> new PluginStatus(entry.label(), entry.state(), entry.message()))
                .toList();
    }

    private void load(PluginEntry entry) {
        var missing = missingHardDependency(entry.descriptor());
        if (missing.isPresent()) {
            fail(entry, "hard dependency '" + missing.get() + "' is not enabled", null);
            return;
        }
        try {
            entry.attach(construct(entry));
        } catch (Exception | LinkageError error) {
            fail(entry, reason(error), error);
            return;
        }
        enable(entry);
    }

    /** LOAD: the jar is opened, the constructor runs with injection, and then load() runs. */
    private IPlugin construct(PluginEntry entry) throws IOException, ReflectiveOperationException {
        var loader = entry.openClassLoader(parent);
        var mainName = entry.descriptor().main();
        var main = loadMainClass(loader, mainName, entry.jar());
        if (!IPlugin.class.isAssignableFrom(main)) {
            throw new IllegalStateException("main class " + mainName + " does not implement " + IPlugin.class.getName());
        }
        var logger = LoggerFactory.getLogger(entry.label());
        var instance = injector.instantiate(main, entry.context(), logger, dependencyContexts(entry.descriptor()));
        var plugin = (IPlugin) instance;
        plugin.load();
        return plugin;
    }

    private void enable(PluginEntry entry) {
        try {
            entry.plugin().enable();
        } catch (Exception | LinkageError error) {
            // A plugin that failed in enable() may have registered things, so disable() still runs once.
            runDisable(entry);
            fail(entry, "enable() failed: " + reason(error), error);
            return;
        }
        entry.markEnabled();
        enabled.add(entry);
        LOGGER.info("Enabled plugin {} {}", entry.label(), entry.descriptor().version());
    }

    private void runDisable(PluginEntry entry) {
        try {
            entry.plugin().disable();
        } catch (Exception | LinkageError error) {
            LOGGER.error("Plugin {} threw in disable()", entry.label(), error);
        }
    }

    private void fail(PluginEntry entry, String reason, Throwable cause) {
        release(entry);
        entry.markDisabled(reason);
        LOGGER.error("Plugin {} is disabled: {}", entry.label(), reason, cause);
    }

    /** Unpublishes the services and closes the class loader, so a disabled plugin holds nothing. */
    private static void release(PluginEntry entry) {
        entry.context().close();
        try {
            entry.closeClassLoader();
        } catch (IOException error) {
            LOGGER.warn("Could not close the class loader of plugin {}", entry.label(), error);
        }
    }

    private Optional<String> missingHardDependency(PluginDescriptor descriptor) {
        return descriptor.depends().stream()
                .filter(id -> enabled.stream().noneMatch(entry -> entry.label().equals(id)))
                .findFirst();
    }

    private List<PluginContext> dependencyContexts(PluginDescriptor descriptor) {
        return enabled.stream()
                .filter(entry -> descriptor.depends().contains(entry.label()))
                .map(PluginEntry::context)
                .toList();
    }

    private List<Path> listJars() {
        try {
            Files.createDirectories(directory);
            try (var files = Files.list(directory)) {
                return files.filter(file -> file.getFileName().toString().endsWith(JAR_SUFFIX))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .toList();
            }
        } catch (IOException error) {
            LOGGER.error("Could not read the plugins directory {}", directory.toAbsolutePath(), error);
            return List.of();
        }
    }

    private PluginEntry read(Path jar) {
        try {
            var text = readDescriptor(jar);
            if (text.isEmpty()) {
                return failed(jar, "missing " + PluginDescriptor.FILE_NAME);
            }
            return new PluginEntry(jar, PluginDescriptor.parse(text.get()), events, commands);
        } catch (IOException error) {
            return failed(jar, "cannot read " + PluginDescriptor.FILE_NAME + ": " + reason(error));
        } catch (IllegalArgumentException error) {
            return failed(jar, "invalid " + PluginDescriptor.FILE_NAME + ": " + reason(error));
        }
    }

    private PluginEntry failed(Path jar, String reason) {
        var entry = new PluginEntry(jar, null, events, commands);
        fail(entry, reason, null);
        return entry;
    }

    private PluginEntry entryOf(PluginCandidate candidate) {
        return entries.stream()
                .filter(entry -> entry.jar().equals(candidate.jar()))
                .findFirst()
                .orElseThrow();
    }

    private static Optional<String> readDescriptor(Path jar) throws IOException {
        try (var file = new JarFile(jar.toFile())) {
            var descriptor = file.getJarEntry(PluginDescriptor.FILE_NAME);
            if (descriptor == null) {
                return Optional.empty();
            }
            try (var input = file.getInputStream(descriptor)) {
                return Optional.of(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private static Class<?> loadMainClass(ClassLoader loader, String name, Path jar) {
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException error) {
            throw new IllegalStateException("main class " + name + " not found in " + jar.getFileName(), error);
        }
    }

    private static String reason(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    private static void requireArgument(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }
}
