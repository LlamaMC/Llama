package com.llamamc.llama.world;

import com.llamamc.llama.config.WorldsConfig;
import com.llamamc.llama.config.WorldsConfig.ConfigWorld;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.IWorld;
import com.llamamc.llamaapi.world.WorldType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(30)
final class WorldManagerOrderTest {
    @TempDir
    Path directory;

    @Test
    void worldsKeepTheConfiguredOrder() throws IOException {
        var worlds = new LinkedHashMap<String, ConfigWorld>();
        worlds.put("zeta", new ConfigWorld("_llama", WorldType.FLAT, DimensionType.OVERWORLD));
        worlds.put("alpha", new ConfigWorld("_llama", WorldType.FLAT, DimensionType.OVERWORLD));
        worlds.put("mid", new ConfigWorld("_llama", WorldType.FLAT, DimensionType.OVERWORLD));
        var config = new WorldsConfig();
        config.setWorlds(worlds);

        try (var manager = new WorldManager(config, directory)) {
            var names = manager.worlds().stream().map(IWorld::name).toList();

            assertEquals(List.of("zeta", "alpha", "mid"), names);
        }
    }
}
