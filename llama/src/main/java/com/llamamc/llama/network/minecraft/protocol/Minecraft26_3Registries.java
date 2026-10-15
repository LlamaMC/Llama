package com.llamamc.llama.network.minecraft.protocol;

import com.google.gson.JsonParser;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.RegistryDataPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.UpdateTagsPacket;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public final class Minecraft26_3Registries {
    private static final String RESOURCE = "/minecraft/26.3/registries.json";
    private static final String TAGS_RESOURCE = "/minecraft/26.3/tags.json";
    private static final List<RegistryDataPacket> PACKETS = load();
    private static final UpdateTagsPacket TAGS = loadTags();

    private Minecraft26_3Registries() {
    }

    public static List<RegistryDataPacket> packets() {
        return PACKETS;
    }

    public static UpdateTagsPacket tags() {
        return TAGS;
    }

    private static List<RegistryDataPacket> load() {
        try (var stream = Minecraft26_3Registries.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing Minecraft registry data: " + RESOURCE);
            }

            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var registries = JsonParser.parseReader(reader).getAsJsonArray();
                var packets = new ArrayList<RegistryDataPacket>(registries.size());

                for (var registryElement : registries) {
                    var registry = registryElement.getAsJsonObject();
                    var entries = new ArrayList<RegistryDataPacket.Entry>();

                    for (var entry : registry.getAsJsonArray("entries")) {
                        entries.add(new RegistryDataPacket.Entry(entry.getAsString()));
                    }

                    packets.add(new RegistryDataPacket(registry.get("registry").getAsString(), entries));
                }

                return List.copyOf(packets);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load Minecraft registry data: " + RESOURCE, error);
        }
    }

    private static UpdateTagsPacket loadTags() {
        try (var stream = Minecraft26_3Registries.class.getResourceAsStream(TAGS_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing Minecraft tag data: " + TAGS_RESOURCE);
            }

            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var root = JsonParser.parseReader(reader).getAsJsonObject();
                var registries = new LinkedHashMap<String, List<UpdateTagsPacket.Tag>>();

                for (var registry : root.entrySet()) {
                    var tags = new ArrayList<UpdateTagsPacket.Tag>();

                    for (var tagElement : registry.getValue().getAsJsonArray()) {
                        var tag = tagElement.getAsJsonObject();
                        var entries = new ArrayList<Integer>();

                        for (var entry : tag.getAsJsonArray("entries")) {
                            entries.add(entry.getAsInt());
                        }

                        tags.add(new UpdateTagsPacket.Tag(tag.get("identifier").getAsString(), entries));
                    }

                    registries.put(registry.getKey(), tags);
                }

                return new UpdateTagsPacket(registries);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load Minecraft tag data: " + TAGS_RESOURCE, error);
        }
    }
}
