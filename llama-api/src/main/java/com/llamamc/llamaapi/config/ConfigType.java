package com.llamamc.llamaapi.config;

public enum ConfigType {
    JSON(".json"),
    YAML(".yml"),
    TOML(".toml"),
    PROPERTIES(".properties");

    private final String extension;

    ConfigType(String extension) {
        this.extension = extension;
    }

    public String getExtension() {
        return extension;
    }
}
