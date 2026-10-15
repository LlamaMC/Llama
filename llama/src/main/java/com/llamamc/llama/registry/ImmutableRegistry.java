package com.llamamc.llama.registry;

import com.llamamc.llamaapi.registry.IRegistry;
import com.llamamc.llamaapi.registry.IRegistryEntry;
import com.llamamc.llamaapi.registry.NamespacedKey;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

final class ImmutableRegistry<T extends IRegistryEntry> implements IRegistry<T> {
    private final Map<NamespacedKey, T> entries;
    private final List<T> values;

    ImmutableRegistry(Collection<T> entries) {
        var indexed = new LinkedHashMap<NamespacedKey, T>();
        for (var entry : entries) {
            if (indexed.put(entry.key(), entry) != null) {
                throw new IllegalArgumentException("Duplicate registry entry: " + entry.key());
            }
        }
        this.entries = Map.copyOf(indexed);
        values = List.copyOf(indexed.values());
    }

    @Override
    public Optional<T> find(NamespacedKey key) {
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public T require(NamespacedKey key) {
        var entry = entries.get(key);
        if (entry != null) {
            return entry;
        }
        throw new NoSuchElementException("Unknown registry entry: " + key);
    }

    @Override
    public Collection<T> values() {
        return values;
    }
}
