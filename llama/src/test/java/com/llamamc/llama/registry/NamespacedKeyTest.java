package com.llamamc.llama.registry;

import com.llamamc.llamaapi.registry.NamespacedKey;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NamespacedKeyTest {
    @Test
    void tryParseReturnsValidKey() {
        assertEquals(Optional.of(NamespacedKey.minecraft("stone")), NamespacedKey.tryParse("minecraft:stone"));
    }

    @Test
    void tryParseReturnsEmptyForInvalidInput() {
        assertEquals(Optional.empty(), NamespacedKey.tryParse("Bad Key!"));
        assertEquals(Optional.empty(), NamespacedKey.tryParse(""));
        assertEquals(Optional.empty(), NamespacedKey.tryParse(null));
    }
}
