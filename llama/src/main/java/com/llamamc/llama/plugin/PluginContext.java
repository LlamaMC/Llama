package com.llamamc.llama.plugin;

import com.llamamc.llamaapi.command.ICommandRegistry;
import com.llamamc.llamaapi.command.ICommandSender;
import com.llamamc.llamaapi.event.EventPriority;
import com.llamamc.llamaapi.event.IEvent;
import com.llamamc.llamaapi.event.IEventBus;
import com.llamamc.llamaapi.event.IRegistration;
import com.llamamc.llamaapi.plugin.IPluginContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The runtime handle of one plugin. The plugin reaches the event bus and the command registry only through the views
 * made here, so closing the context removes every handler and command the plugin registered.
 */
final class PluginContext implements IPluginContext {

    private final String pluginId;
    private final Map<Class<?>, Object> services = new HashMap<>();
    private final EventBusView events;
    private final CommandRegistryView commands;
    private boolean open = true;

    PluginContext(String pluginId, IEventBus events, ICommandRegistry commands) {
        this.pluginId = pluginId;
        this.events = new EventBusView(events);
        this.commands = new CommandRegistryView(commands);
    }

    @Override
    public String pluginId() {
        return pluginId;
    }

    @Override
    public synchronized <T> void provide(Class<T> type, T service) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (service == null) {
            throw new IllegalArgumentException("service must not be null");
        }
        if (!type.isInstance(service)) {
            throw new IllegalArgumentException("service is not a " + type.getName());
        }
        if (!open) {
            throw new IllegalStateException("plugin " + pluginId + " is disabled");
        }
        if (services.containsKey(type)) {
            throw new IllegalStateException("plugin " + pluginId + " already provides " + type.getName());
        }
        services.put(type, service);
    }

    synchronized Map<Class<?>, Object> published() {
        return Map.copyOf(services);
    }

    IEventBus events() {
        return events;
    }

    ICommandRegistry commands() {
        return commands;
    }

    /** Unpublishes the services, then removes the handlers and commands. A second call does nothing. */
    synchronized void close() {
        if (!open) {
            return;
        }
        open = false;
        services.clear();
        events.close();
        commands.close();
    }

    private void requireOpen() {
        if (!open) {
            throw new IllegalStateException("plugin " + pluginId + " is disabled");
        }
    }

    /** Forwards to the shared bus and keeps every registration, so that close can remove them all. */
    private final class EventBusView implements IEventBus {

        private final IEventBus shared;
        private final List<IRegistration> registrations = new ArrayList<>();

        private EventBusView(IEventBus shared) {
            this.shared = shared;
        }

        @Override
        public <E extends IEvent> IRegistration on(Class<E> type, Consumer<? super E> handler) {
            return on(type, EventPriority.NORMAL, handler);
        }

        @Override
        public <E extends IEvent> IRegistration on(Class<E> type, EventPriority priority,
                                                   Consumer<? super E> handler) {
            synchronized (PluginContext.this) {
                requireOpen();
                var registration = shared.on(type, priority, handler);
                registrations.add(registration);
                return registration;
            }
        }

        @Override
        public void post(IEvent event) {
            shared.post(event);
        }

        private void close() {
            registrations.forEach(IRegistration::close);
            registrations.clear();
        }
    }

    /**
     * Every command registered through this view is owned by the view itself, so the owner the plugin passes is not
     * used. A plugin therefore cannot register on behalf of the runtime or of another plugin, and closing the context
     * removes all of its commands.
     */
    private final class CommandRegistryView implements ICommandRegistry {

        private final ICommandRegistry shared;

        private CommandRegistryView(ICommandRegistry shared) {
            this.shared = shared;
        }

        @Override
        public void register(Object owner, Object handler) {
            synchronized (PluginContext.this) {
                requireOpen();
                shared.register(this, handler);
            }
        }

        @Override
        public boolean unregister(Object owner) {
            synchronized (PluginContext.this) {
                return shared.unregister(this);
            }
        }

        @Override
        public boolean dispatch(ICommandSender sender, String line) {
            return shared.dispatch(sender, line);
        }

        @Override
        public List<String> commandNames() {
            return shared.commandNames();
        }

        private void close() {
            shared.unregister(this);
        }
    }
}
