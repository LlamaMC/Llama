package com.llamamc.llamaapi.component;

public final class Component {
    private String text;

    private Component(String text) {
        this.text = text;
    }

    public Component coloring() {
        // TODO: MiniMessage colour deserializer
        return this;
    }

    public Component resolve(Placeholder... placeholders) {
        for (Placeholder placeholder : placeholders) {
            text = text.replaceAll(placeholder.identifier(), placeholder.replacement().text);
        }
        return this;
    }

    public static Component parse(String text) {
        return new Component(text);
    }

    public static Component append(Component component) {
        return component; // TODO: APPEND
    }
}
