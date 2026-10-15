package com.llamamc.llamaapi.registry;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public record NamespacedKey(String namespace, String value) implements Comparable<NamespacedKey> {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern VALUE = Pattern.compile("[a-z0-9_./-]+");
    private static final String MINECRAFT_NAMESPACE = "minecraft";

    public NamespacedKey {
        namespace = normalize(namespace, "namespace");
        value = normalize(value, "value");
        if (!NAMESPACE.matcher(namespace).matches() || !VALUE.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid namespaced key: " + namespace + ":" + value);
        }
    }

    public static NamespacedKey parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Namespaced key cannot be blank");
        }

        var separator = input.indexOf(':');
        if (separator < 0) {
            return minecraft(input);
        }
        return new NamespacedKey(input.substring(0, separator), input.substring(separator + 1));
    }

    public static Optional<NamespacedKey> tryParse(String input) {
        if (input == null || input.isBlank()) {
            return Optional.empty();
        }

        var separator = input.indexOf(':');
        var namespace = separator < 0 ? MINECRAFT_NAMESPACE : input.substring(0, separator);
        var value = separator < 0 ? input : input.substring(separator + 1);
        if (!isValid(namespace, value)) {
            return Optional.empty();
        }
        return Optional.of(new NamespacedKey(namespace, value));
    }

    public static NamespacedKey minecraft(String value) {
        return new NamespacedKey(MINECRAFT_NAMESPACE, value);
    }

    @Override
    public int compareTo(NamespacedKey other) {
        return toString().compareTo(other.toString());
    }

    @Override
    public String toString() {
        return namespace + ":" + value;
    }

    private static String normalize(String input, String subject) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException(subject + " cannot be blank");
        }
        return input.toLowerCase(Locale.ROOT);
    }

    private static boolean isValid(String namespace, String value) {
        return NAMESPACE.matcher(namespace.toLowerCase(Locale.ROOT)).matches()
                && VALUE.matcher(value.toLowerCase(Locale.ROOT)).matches();
    }
}
