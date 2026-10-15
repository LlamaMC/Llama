package com.llamamc.llama.config;

import com.llamamc.llamaapi.config.Config;
import com.llamamc.llamaapi.config.Key;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.WorldType;

import java.util.Map;
import java.util.LinkedHashMap;

@Config(name = "worlds")
public class WorldsConfig {

    @Key("worlds")
    private Map<String, ConfigWorld> worlds;

    public void setWorlds(Map<String, ?> worlds) {
        var converted = new LinkedHashMap<String, ConfigWorld>();
        worlds.forEach((name, value) -> converted.put(name, ConfigWorld.from(value)));
        this.worlds = converted;
    }

    public Map<String, ConfigWorld> getWorlds() {
        return worlds;
    }

    public static class ConfigWorld {
        private String format;
        private String seed;
        private WorldType type;
        private DimensionType dimension;

        public ConfigWorld() {
        }

        public ConfigWorld(String format, WorldType type, DimensionType dimension) {
            this(format, "0", type, dimension);
        }

        public ConfigWorld(String format, String seed, WorldType type, DimensionType dimension) {
            this.format = format;
            this.seed = seed;
            this.type = type;
            this.dimension = dimension;
        }

        public String getFormat() {
            return format;
        }

        public WorldType getType() {
            return type;
        }

        public String getSeed() {
            return seed;
        }

        public DimensionType getDimension() {
            return dimension;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public void setSeed(String seed) {
            this.seed = seed;
        }

        public void setType(WorldType type) {
            this.type = type;
        }

        public void setDimension(DimensionType dimension) {
            this.dimension = dimension;
        }

        private static ConfigWorld from(Object value) {
            if (value instanceof ConfigWorld world) {
                return world;
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("World configuration must be a table");
            }

            var format = text(map, "format", "_llama");
            var seed = text(map, "seed", "0");
            var type = WorldType.valueOf(text(map, "type", WorldType.NORMAL.name()).toUpperCase(java.util.Locale.ROOT));
            var dimension = DimensionType.valueOf(text(map, "dimension", DimensionType.OVERWORLD.name())
                    .toUpperCase(java.util.Locale.ROOT));
            return new ConfigWorld(format, seed, type, dimension);
        }

        private static String text(Map<?, ?> map, String key, String fallback) {
            var value = map.get(key);
            return value == null ? fallback : value.toString();
        }
    }
}
