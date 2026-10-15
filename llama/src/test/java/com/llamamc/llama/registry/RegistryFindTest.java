package com.llamamc.llama.registry;

import com.llamamc.llamaapi.registry.IRegistry;
import com.llamamc.llamaapi.registry.IRegistryEntry;
import com.llamamc.llamaapi.registry.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RegistryFindTest {
    private record Entry(NamespacedKey key) implements IRegistryEntry {
    }

    @Test
    void findByStringReturnsEntryForValidKey() {
        var registry = singleEntryRegistry();

        assertEquals(Optional.of(new Entry(NamespacedKey.minecraft("stone"))), registry.find("minecraft:stone"));
    }

    @Test
    void findByStringReturnsEmptyForInvalidKeyWithoutThrowing() {
        var registry = singleEntryRegistry();

        var found = assertDoesNotThrow(() -> registry.find("Bad Key!"));

        assertEquals(Optional.empty(), found);
    }

    private static IRegistry<Entry> singleEntryRegistry() {
        var entry = new Entry(NamespacedKey.minecraft("stone"));
        return new IRegistry<>() {
            @Override
            public Optional<Entry> find(NamespacedKey key) {
                return entry.key().equals(key) ? Optional.of(entry) : Optional.empty();
            }

            @Override
            public Entry require(NamespacedKey key) {
                return find(key).orElseThrow();
            }

            @Override
            public Collection<Entry> values() {
                return List.of(entry);
            }
        };
    }
}
