package com.llamamc.llama.command;

import com.llamamc.llamaapi.command.Argument;
import com.llamamc.llamaapi.command.Command;
import com.llamamc.llamaapi.command.ICommand;
import com.llamamc.llamaapi.command.ICommandContext;
import com.llamamc.llamaapi.component.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// One public method of a handler as a command. Arguments are parsed before the method runs; a rejected line gets a
// reply and the method is not called.
final class MethodCommand implements ICommand {
    private final Object handler;
    private final Method method;
    private final List<Slot> slots;
    private final String usage;

    private MethodCommand(Object handler, Method method, String name, List<Slot> slots) {
        this.handler = handler;
        this.method = method;
        this.slots = slots;
        this.usage = usage(name, slots);
    }

    static MethodCommand of(Object handler, Method method, Command annotation) {
        var slots = slots(method);
        method.setAccessible(true);
        return new MethodCommand(handler, method, annotation.name(), slots);
    }

    @Override
    public void execute(ICommandContext context) {
        Object[] values;
        try {
            values = bind(context);
        } catch (Rejected rejected) {
            context.reply(Component.of(rejected.getMessage()));
            return;
        }
        invoke(values);
    }

    private Object[] bind(ICommandContext context) {
        var arguments = context.arguments();
        if (arguments.size() > slots.size()) {
            throw new Rejected(usage);
        }
        var values = new Object[method.getParameterCount()];
        values[0] = context;

        for (var index = 0; index < slots.size(); index++) {
            var slot = slots.get(index);
            if (index < arguments.size()) {
                values[slot.index()] = parse(slot, arguments.get(index));
            } else if (slot.required()) {
                throw new Rejected(usage);
            }
        }
        return values;
    }

    private static Object parse(Slot slot, String text) {
        try {
            return slot.kind().parse(text);
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new Rejected("Invalid %s '%s': expected %s".formatted(slot.name(), text, slot.kind().description()));
        }
    }

    private void invoke(Object[] values) {
        try {
            method.invoke(handler, values);
        } catch (InvocationTargetException error) {
            rethrow(error.getCause());
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("Cannot invoke command method " + method.getName(), error);
        }
    }

    // Runtime exceptions and errors keep their type, so the registry reports them as before.
    private static void rethrow(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException("Command method failed: " + cause, cause);
    }

    private static List<Slot> slots(Method method) {
        if (!Modifier.isPublic(method.getModifiers())) {
            throw invalid(method, "the method must be public");
        }
        if (method.getReturnType() != void.class) {
            throw invalid(method, "the method must return void");
        }
        var parameters = method.getParameters();
        if (parameters.length == 0 || parameters[0].getType() != ICommandContext.class) {
            throw invalid(method, "the first parameter must be ICommandContext");
        }
        var slots = new ArrayList<Slot>(parameters.length - 1);
        for (var index = 1; index < parameters.length; index++) {
            slots.add(slot(method, parameters[index], index));
        }
        slots.sort(Comparator.comparingInt(Slot::position));
        checkOrder(method, slots);
        return List.copyOf(slots);
    }

    private static Slot slot(Method method, Parameter parameter, int index) {
        var argument = parameter.getAnnotation(Argument.class);
        if (argument == null) {
            throw invalid(method, "parameter " + index + " has no @Argument");
        }
        if (argument.name().isBlank()) {
            throw invalid(method, "parameter " + index + " has a blank @Argument name");
        }
        var kind = ArgumentKind.fromJavaType(parameter.getType());
        if (kind.isEmpty()) {
            throw invalid(method, "parameter '%s' has unsupported type %s"
                    .formatted(argument.name(), parameter.getType().getName()));
        }
        if (ArgumentKind.fromArgumentType(argument.type()) != kind.get()) {
            throw invalid(method, "parameter '%s' is %s but its @Argument type is %s"
                    .formatted(argument.name(), parameter.getType().getName(), argument.type()));
        }
        if (argument.position() < 0) {
            throw invalid(method, "parameter '%s' needs a position of 0 or more".formatted(argument.name()));
        }
        if (!argument.required() && parameter.getType().isPrimitive()) {
            throw invalid(method, "optional parameter '%s' cannot be a primitive".formatted(argument.name()));
        }
        return new Slot(argument.name(), argument.position(), index, kind.get(), argument.required());
    }

    private static void checkOrder(Method method, List<Slot> slots) {
        for (var index = 1; index < slots.size(); index++) {
            var previous = slots.get(index - 1);
            var slot = slots.get(index);
            if (previous.position() == slot.position()) {
                throw invalid(method, "positions must be unique, but '%s' and '%s' share position %d"
                        .formatted(previous.name(), slot.name(), slot.position()));
            }
            if (!previous.required() && slot.required()) {
                throw invalid(method, "required parameter '%s' follows optional parameter '%s'"
                        .formatted(slot.name(), previous.name()));
            }
        }
    }

    private static String usage(String name, List<Slot> slots) {
        var text = new StringBuilder("Usage: ").append(name);
        for (var slot : slots) {
            text.append(' ').append(slot.required() ? "<%s>".formatted(slot.name()) : "[%s]".formatted(slot.name()));
        }
        return text.toString();
    }

    private static IllegalArgumentException invalid(Method method, String reason) {
        return new IllegalArgumentException("Invalid command method %s.%s: %s"
                .formatted(method.getDeclaringClass().getName(), method.getName(), reason));
    }

    // index is the parameter's place in the method signature, which differs from position when declared out of order.
    private record Slot(String name, int position, int index, ArgumentKind kind, boolean required) {
    }

    private static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Rejected(String message) {
            super(message);
        }
    }
}
