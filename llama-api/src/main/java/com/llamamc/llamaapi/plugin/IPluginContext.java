package com.llamamc.llamaapi.plugin;

public interface IPluginContext {

    String pluginId();

    /**
     * Publishes a service under the given type. A plugin with a hard dependency on this plugin receives it through its
     * constructor. The service is unpublished when this plugin is disabled.
     *
     * @throws IllegalArgumentException if the type or the service is null, or the service is not an instance of the type
     * @throws IllegalStateException if this plugin already provides a service of the type, or it is disabled
     */
    <T> void provide(Class<T> type, T service);

}
