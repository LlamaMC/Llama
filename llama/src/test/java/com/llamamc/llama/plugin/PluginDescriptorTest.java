package com.llamamc.llama.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class PluginDescriptorTest {

    @Test
    void parsesEveryField() {
        var descriptor = PluginDescriptor.parse("""
                {
                  "id": "economy",
                  "name": "Economy",
                  "version": "2.1.0",
                  "main": "com.example.EconomyPlugin",
                  "depends": ["permissions"],
                  "softDepends": ["chat", "shops"]
                }
                """);

        assertEquals("economy", descriptor.id());
        assertEquals("Economy", descriptor.name());
        assertEquals("2.1.0", descriptor.version());
        assertEquals("com.example.EconomyPlugin", descriptor.main());
        assertEquals(List.of("permissions"), descriptor.depends());
        assertEquals(List.of("chat", "shops"), descriptor.softDepends());
    }

    @Test
    void dependencyListsDefaultToEmpty() {
        var descriptor = PluginDescriptor.parse(
                "{\"id\": \"chat\", \"name\": \"Chat\", \"version\": \"1.0.0\", \"main\": \"com.example.ChatPlugin\"}");

        assertEquals(List.of(), descriptor.depends());
        assertEquals(List.of(), descriptor.softDepends());
    }

    @Test
    void missingMainIsRejected() {
        var error = assertThrows(IllegalArgumentException.class, () -> PluginDescriptor.parse(
                "{\"id\": \"chat\", \"name\": \"Chat\", \"version\": \"1.0.0\"}"));

        assertEquals("missing required field 'main'", error.getMessage());
    }

    @Test
    void invalidJsonIsRejected() {
        var error = assertThrows(IllegalArgumentException.class, () -> PluginDescriptor.parse("{\"id\": \"chat\", "));

        assertTrue(error.getMessage().startsWith("not valid JSON"), error.getMessage());
    }

    @Test
    void topLevelArrayIsRejected() {
        var error = assertThrows(IllegalArgumentException.class, () -> PluginDescriptor.parse("[]"));

        assertEquals("the top level must be a JSON object", error.getMessage());
    }

    @Test
    void blankIdIsRejected() {
        var error = assertThrows(IllegalArgumentException.class, () -> PluginDescriptor.parse(
                "{\"id\": \" \", \"name\": \"Chat\", \"version\": \"1.0.0\", \"main\": \"com.example.ChatPlugin\"}"));

        assertEquals("field 'id' must be a non-blank string", error.getMessage());
    }

    @Test
    void dependsMustBeAnArray() {
        var error = assertThrows(IllegalArgumentException.class, () -> PluginDescriptor.parse(
                "{\"id\": \"chat\", \"name\": \"Chat\", \"version\": \"1.0.0\", \"main\": \"com.example.ChatPlugin\","
                        + " \"depends\": \"economy\"}"));

        assertEquals("field 'depends' must be an array of plugin ids", error.getMessage());
    }
}
