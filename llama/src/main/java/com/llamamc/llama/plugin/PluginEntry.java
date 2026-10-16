package com.llamamc.llama.plugin;

import com.llamamc.llamaapi.command.ICommandRegistry;
import com.llamamc.llamaapi.event.IEventBus;
import com.llamamc.llamaapi.plugin.IPlugin;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * One plugin jar and everything the loader holds for it. Only {@link PluginLoader} changes it.
 */
final class PluginEntry {

    private final Path jar;
    private final PluginDescriptor descriptor;
    private final String label;
    private final PluginContext context;
    private PluginState state = PluginState.LOADING;
    private String message;
    private URLClassLoader classLoader;
    private IPlugin plugin;

    PluginEntry(Path jar, PluginDescriptor descriptor, IEventBus events, ICommandRegistry commands) {
        this.jar = jar;
        this.descriptor = descriptor;
        this.label = descriptor == null ? jar.getFileName().toString() : descriptor.id();
        this.context = new PluginContext(label, events, commands);
    }

    Path jar() {
        return jar;
    }

    /** Null when the descriptor could not be read. */
    PluginDescriptor descriptor() {
        return descriptor;
    }

    String label() {
        return label;
    }

    PluginContext context() {
        return context;
    }

    PluginState state() {
        return state;
    }

    String message() {
        return message;
    }

    IPlugin plugin() {
        return plugin;
    }

    URLClassLoader openClassLoader(ClassLoader parent) throws IOException {
        classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, parent);
        return classLoader;
    }

    void closeClassLoader() throws IOException {
        if (classLoader == null) {
            return;
        }
        classLoader.close();
    }

    void attach(IPlugin plugin) {
        this.plugin = plugin;
    }

    void markEnabled() {
        state = PluginState.ENABLED;
    }

    void markDisabled(String reason) {
        state = PluginState.DISABLED;
        message = reason;
    }
}
