package com.llamamc.llama.component;

import com.llamamc.llamaapi.component.Component;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComponentTest {
    @Test
    void keepsDollarAndBackslashInReplacementValueLiteral() {
        var component = Component.of("a").replace("a", "$1\\");

        assertEquals("$1\\", component.text());
    }

    @Test
    void matchesKeysLiterallyInsteadOfAsPatterns() {
        var component = Component.of("a.* axb").replace("a.*", "X");

        assertEquals("X axb", component.text());
    }

    @Test
    void keepsOriginalInstanceUnchangedAfterReplaceAndAppend() {
        var original = Component.of("a");

        original.replace("a", "b");
        original.append(Component.of("c"));

        assertEquals("a", original.text());
    }

    @Test
    void appendsTailText() {
        assertEquals("ab", Component.of("a").append(Component.of("b")).text());
    }

    @Test
    void rejectsEmptyLiteral() {
        var component = Component.of("a");

        assertThrows(IllegalArgumentException.class, () -> component.replace("", "x"));
    }

    @Test
    void rejectsNullText() {
        assertThrows(IllegalArgumentException.class, () -> Component.of(null));
    }
}
