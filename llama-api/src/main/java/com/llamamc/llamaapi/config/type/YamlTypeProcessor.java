package com.llamamc.llamaapi.config.type;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

public class YamlTypeProcessor implements ITypeProcessor {
    private static final Yaml YAML = new Yaml();

    @Override
    public boolean supports(String fileName) {
        return fileName.endsWith(".yml") || fileName.endsWith(".yaml");
    }

    @Override
    public Map<String, Object> parse(InputStream in) {
        Object obj = YAML.load(in);
        return obj == null ? new HashMap<>() : (Map<String, Object>) obj;
    }

    @Override
    public void write(Writer writer, Map<String, Object> data) {
        YAML.dump(data, writer);
    }
}
