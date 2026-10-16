package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.TreeSet;

/**
 * The persistent identity of a block state: {@code namespace:key[name=value,...]} with the properties sorted by name.
 */
final class BlockStateStrings {
    private static final String RESERVED_CHARACTERS = "[],=";

    private BlockStateStrings() {
    }

    static String format(BlockState state) {
        var properties = state.properties();
        if (properties.isEmpty()) {
            return state.identifier();
        }

        var pairs = new ArrayList<String>();
        for (var name : new TreeSet<>(properties.keySet())) {
            var value = properties.get(name);
            if (!isWritable(name) || !isWritable(value)) {
                throw new IllegalArgumentException("Block state property cannot be stored: " + name + "=" + value);
            }
            pairs.add(name + "=" + value);
        }
        return state.identifier() + "[" + String.join(",", pairs) + "]";
    }

    static BlockState parse(String text) {
        var open = text.indexOf('[');
        if (open < 0) {
            return new BlockState(text);
        }
        if (!text.endsWith("]")) {
            throw new IllegalArgumentException("Block state is missing its closing bracket: " + text);
        }

        var properties = new LinkedHashMap<String, String>();
        var body = text.substring(open + 1, text.length() - 1);
        if (!body.isEmpty()) {
            for (var pair : body.split(",", -1)) {
                addProperty(properties, pair, text);
            }
        }
        return new BlockState(text.substring(0, open), properties);
    }

    private static void addProperty(LinkedHashMap<String, String> properties, String pair, String text) {
        var separator = pair.indexOf('=');
        if (separator < 1 || separator == pair.length() - 1) {
            throw new IllegalArgumentException("Malformed block state property '" + pair + "' in " + text);
        }

        var name = pair.substring(0, separator);
        if (properties.put(name, pair.substring(separator + 1)) != null) {
            throw new IllegalArgumentException("Duplicate block state property '" + name + "' in " + text);
        }
    }

    private static boolean isWritable(String value) {
        return !value.isEmpty() && value.chars().noneMatch(character -> RESERVED_CHARACTERS.indexOf(character) >= 0);
    }
}
