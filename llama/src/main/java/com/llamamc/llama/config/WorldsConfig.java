package com.llamamc.llama.config;

import com.llamamc.llamaapi.config.Config;
import com.llamamc.llamaapi.config.Key;
import com.llamamc.llamaapi.world.DimensionType;
import com.llamamc.llamaapi.world.WorldType;

import java.util.Map;

@Config(name = "worlds")
public class WorldsConfig {

    @Key("worlds")
    private Map<String, ConfigWorld> worlds;

    public void setWorlds(Map<String, ConfigWorld> worlds) {
        this.worlds = worlds;
    }

    public Map<String, ConfigWorld> getWorlds() {
        return worlds;
    }

    public static class ConfigWorld {
        private String format;
        private WorldType type;
        private DimensionType dimension;

        public ConfigWorld(String format, WorldType type, DimensionType dimension) {
            this.format = format;
            this.type = type;
            this.dimension = dimension;
        }

        public String getFormat() {
            return format;
        }

        public WorldType getType() {
            return type;
        }

        public DimensionType getDimension() {
            return dimension;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public void setType(WorldType type) {
            this.type = type;
        }

        public void setDimension(DimensionType dimension) {
            this.dimension = dimension;
        }
    }
}
