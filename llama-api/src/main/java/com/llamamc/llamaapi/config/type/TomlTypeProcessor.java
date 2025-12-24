package com.llamamc.llamaapi.config.type;

import com.moandjiezana.toml.Toml;
import com.moandjiezana.toml.TomlWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

public class TomlTypeProcessor implements ITypeProcessor {
    @Override
    public boolean supports(String fileName) {
        return fileName.endsWith(".toml");
    }

    @Override
    public Map<String, Object> parse(InputStream in) {
        Toml toml = new Toml().read(in);
        return toMap(toml.toMap());
    }

    private Map<String, Object> toMap(Map<String, Object> input) {
        Map<String, Object> out = new HashMap<>();
        for (var e : input.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Map<?, ?> m)
                out.put(e.getKey(), toMap((Map<String, Object>) m));
            else
                out.put(e.getKey(), v);
        }
        return out;
    }

    @Override
    public void write(Writer writer, Map<String,Object> data) throws IOException {
        new TomlWriter().write(data, writer);
    }
}
