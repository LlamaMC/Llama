package com.llamamc.llamaapi.component;

public class Placeholder {
    private final String identifier;
    private final Component replacement;

    protected Placeholder(String identifier, Component replacement) {
        this.identifier = identifier;
        this.replacement = replacement;
    }

    public static Placeholder parse(String identifier, Component replacement) {
        return new Placeholder(identifier, replacement);
    }

    public static Placeholder parse(String identifier, String replacement) {
        return new Placeholder(identifier, Component.parse(replacement));
    }

    public String identifier() {
        return identifier;
    }

    public Component replacement() {
        return replacement;
    }
}
