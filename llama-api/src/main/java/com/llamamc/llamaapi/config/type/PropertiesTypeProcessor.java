package com.llamamc.llamaapi.config.type;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.*;

public class PropertiesTypeProcessor implements ITypeProcessor {

    @Override
    public boolean supports(String fileName) {
        return fileName.endsWith(".properties");
    }

    @Override
    public Map<String, Object> parse(InputStream in) throws Exception {
        Properties props = new Properties();
        props.load(in);
        Map<String, Object> root = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            String value = props.getProperty(key);
            insert(root, key.split("\\."), value);
        }
        return root;
    }

    private void insert(Map<String, Object> root, String[] path, String rawValue) {
        Map<String, Object> current = root;
        for (int i = 0; i < path.length - 1; i++) {
            current = (Map<String, Object>) current.computeIfAbsent(
                    path[i], k -> new HashMap<>()
            );
        }
        Object value;
        if (rawValue.contains(",")) {
            value = Arrays.stream(rawValue.split(","))
                    .map(String::trim)
                    .toList();
        } else {
            value = rawValue;
        }
        current.put(path[path.length - 1], value);
    }

    @Override
    public void write(Writer writer, Map<String, Object> data) throws IOException {
        Properties props = new Properties();
        flattenMap("", data, props);
        props.store(writer, "Auto-Saved Config");
    }

    private void flattenMap(String prefix, Map<String,Object> map, Properties props) {
        for (var entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?,?> sub) {
                flattenMap(key, (Map<String,Object>)sub, props);
            } else if (entry.getValue() instanceof List<?> list) {
                props.setProperty(key, String.join(",", list.stream().map(Object::toString).toList()));
            } else {
                props.setProperty(key, entry.getValue().toString());
            }
        }
    }
}
