package com.llamamc.llamaapi.config.type;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.InputStream;
import java.io.Writer;
import java.util.Map;

public class JsonTypeProcessor implements ITypeProcessor {
    private static final Gson GSON = new Gson();

    @Override
    public boolean supports(String fileName) {
        return fileName.endsWith(".json");
    }

    @Override
    public Map<String, Object> parse(InputStream in) {
        return GSON.fromJson(new java.io.InputStreamReader(in), Map.class);
    }

    @Override
    public void write(Writer writer, Map<String, Object> data) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        gson.toJson(data, writer);
    }
}
