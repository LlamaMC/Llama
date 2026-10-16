package com.llamamc.llama.plugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The contents of a plugin's {@code llama-plugin.json}. The descriptor is the only source of plugin metadata.
 */
public record PluginDescriptor(String id, String name, String version, String main,
                               List<String> depends, List<String> softDepends) {

    public static final String FILE_NAME = "llama-plugin.json";

    public PluginDescriptor {
        if (depends == null || softDepends == null) {
            throw new IllegalArgumentException("dependency lists must not be null");
        }
        depends = List.copyOf(depends);
        softDepends = List.copyOf(softDepends);
    }

    public static PluginDescriptor parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("descriptor text must not be null");
        }
        var object = readObject(json);
        return new PluginDescriptor(
                requiredText(object, "id"),
                requiredText(object, "name"),
                requiredText(object, "version"),
                requiredText(object, "main"),
                optionalIds(object, "depends"),
                optionalIds(object, "softDepends"));
    }

    private static JsonObject readObject(String json) {
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (JsonParseException error) {
            throw new IllegalArgumentException("not valid JSON: " + error.getMessage(), error);
        }
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("the top level must be a JSON object");
        }
        return root.getAsJsonObject();
    }

    private static String requiredText(JsonObject object, String field) {
        var value = object.get(field);
        if (value == null) {
            throw new IllegalArgumentException("missing required field '" + field + "'");
        }
        if (!isText(value) || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("field '" + field + "' must be a non-blank string");
        }
        return value.getAsString();
    }

    private static List<String> optionalIds(JsonObject object, String field) {
        var value = object.get(field);
        if (value == null) {
            return List.of();
        }
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException("field '" + field + "' must be an array of plugin ids");
        }
        var ids = new ArrayList<String>();
        for (var element : value.getAsJsonArray()) {
            if (!isText(element) || element.getAsString().isBlank()) {
                throw new IllegalArgumentException("field '" + field + "' must contain only non-blank strings");
            }
            ids.add(element.getAsString());
        }
        return ids;
    }

    private static boolean isText(JsonElement element) {
        return element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }
}
