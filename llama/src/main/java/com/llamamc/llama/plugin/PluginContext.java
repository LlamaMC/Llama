package com.llamamc.llama.plugin;

import com.llamamc.llamaapi.plugin.IPluginContext;

import java.util.HashMap;
import java.util.Map;

final class PluginContext implements IPluginContext {

    private final String pluginId;
    private final Map<Class<?>, Object> services = new HashMap<>();
    private boolean open = true;

    PluginContext(String pluginId) {
        this.pluginId = pluginId;
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

    synchronized void close() {
        open = false;
        services.clear();
    }
}
