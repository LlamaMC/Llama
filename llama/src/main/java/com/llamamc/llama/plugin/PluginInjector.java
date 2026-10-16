package com.llamamc.llama.plugin;

import com.llamamc.llamaapi.di.annotation.Inject;
import com.llamamc.llamaapi.plugin.IPluginContext;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Creates plugin instances through one constructor. Each parameter is either a shared server service, the plugin's own
 * context or logger, or a service published by one of its hard dependencies. Anything else is an error.
 */
final class PluginInjector {

    private final Map<Class<?>, Object> services;

    PluginInjector(Map<Class<?>, Object> services) {
        this.services = services;
    }

    Object instantiate(Class<?> type, PluginContext context, Logger logger, List<PluginContext> dependencies)
            throws ReflectiveOperationException {
        var constructor = selectConstructor(type);
        var parameters = constructor.getParameterTypes();
        var arguments = new Object[parameters.length];
        for (var index = 0; index < parameters.length; index++) {
            arguments[index] = resolve(parameters[index], context, logger, dependencies);
        }
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("constructor of " + type.getName() + " threw " + error.getTargetException(),
                    error.getTargetException());
        }
    }

    static Constructor<?> selectConstructor(Class<?> type) {
        var constructors = type.getConstructors();
        if (constructors.length == 0) {
            throw new IllegalStateException("main class " + type.getName() + " has no public constructor");
        }
        var annotated = Arrays.stream(constructors).filter(constructor -> constructor.isAnnotationPresent(Inject.class))
                .toList();
        if (annotated.size() > 1) {
            throw new IllegalStateException(type.getName() + " has several constructors annotated @Inject");
        }
        if (annotated.size() == 1) {
            return annotated.getFirst();
        }
        if (constructors.length == 1) {
            return constructors[0];
        }
        var widest = Arrays.stream(constructors).mapToInt(Constructor::getParameterCount).max().orElseThrow();
        var widestConstructors = Arrays.stream(constructors)
                .filter(constructor -> constructor.getParameterCount() == widest)
                .toList();
        if (widestConstructors.size() != 1) {
            throw new IllegalStateException(type.getName() + " has no unique constructor with the most parameters");
        }
        return widestConstructors.getFirst();
    }

    private Object resolve(Class<?> type, PluginContext context, Logger logger, List<PluginContext> dependencies) {
        if (type == IPluginContext.class) {
            return context;
        }
        if (type == Logger.class) {
            return logger;
        }
        var shared = services.get(type);
        if (shared != null) {
            return shared;
        }
        var matches = dependencies.stream()
                .flatMap(dependency -> dependency.published().entrySet().stream())
                .filter(service -> type.isAssignableFrom(service.getKey()))
                .map(Map.Entry::getValue)
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException("cannot inject " + type.getName()
                    + ": it is not a supported type and no hard dependency publishes it");
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("ambiguous injection of " + type.getName() + ": "
                    + matches.size() + " hard dependencies publish it");
        }
        return matches.getFirst();
    }
}
