package com.llamamc.llama.config;

import com.llamamc.llamaapi.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigFactoryTest {
    @Test
    void classWithoutConfigAnnotationFailsWithItsName() {
        var name = UnannotatedConfig.class.getName();

        var error = assertThrows(RuntimeException.class, () -> ConfigFactory.load(UnannotatedConfig.class));

        assertEquals("Could not load config " + name, error.getMessage());
        assertTrue(error.getCause().getMessage().contains(name));
    }

    private static final class UnannotatedConfig {
    }
}
