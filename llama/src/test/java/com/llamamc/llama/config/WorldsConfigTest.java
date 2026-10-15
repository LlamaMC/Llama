package com.llamamc.llama.config;

import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.WorldType;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldsConfigTest {
    @Test
    void convertsTomlTablesToTypedWorlds() {
        var config = new WorldsConfig();
        config.setWorlds(Map.of("main", Map.of(
                "format", "_llama",
                "seed", "12345",
                "type", "flat",
                "dimension", "overworld")));

        var world = config.getWorlds().get("main");
        assertEquals("_llama", world.getFormat());
        assertEquals("12345", world.getSeed());
        assertEquals(WorldType.FLAT, world.getType());
        assertEquals(DimensionType.OVERWORLD, world.getDimension());
    }

    @Test
    void suppliesDefaultsForOlderWorldConfigs() {
        var config = new WorldsConfig();
        config.setWorlds(Map.of("main", Map.of()));

        var world = config.getWorlds().get("main");
        assertEquals("0", world.getSeed());
        assertEquals(WorldType.NORMAL, world.getType());
        assertEquals(DimensionType.OVERWORLD, world.getDimension());
    }
}
