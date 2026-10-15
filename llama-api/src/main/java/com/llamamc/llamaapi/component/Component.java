package com.llamamc.llamaapi.component;

public record Component(String text) {

    public Component {
        if (text == null) {
            throw new IllegalArgumentException("Component text must not be null");
        }
    }

    public static Component of(String text) {
        return new Component(text);
    }

    public Component append(Component tail) {
        if (tail == null) {
            throw new IllegalArgumentException("Component tail must not be null");
        }
        return new Component(text + tail.text());
    }

    // Literal matching: a regex would read keys such as "a.*" as patterns, and "$" or "\" in value as group references.
    public Component replace(String literal, String value) {
        if (literal == null || literal.isEmpty()) {
            throw new IllegalArgumentException("Replacement literal must not be empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("Replacement value must not be null");
        }
        return new Component(text.replace(literal, value));
    }

    public Component replace(String literal, Component value) {
        if (value == null) {
            throw new IllegalArgumentException("Replacement value must not be null");
        }
        return replace(literal, value.text());
    }
}
