package com.llamamc.llamaapi.network.minecraft.packet.configuration;

import com.llamamc.llamaapi.network.protocol.IPacket;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record UpdateTagsPacket(Map<String, List<Tag>> registries) implements IPacket {
    public UpdateTagsPacket {
        var copiedRegistries = new LinkedHashMap<String, List<Tag>>();
        registries.forEach((registry, tags) -> copiedRegistries.put(registry, List.copyOf(tags)));
        registries = Collections.unmodifiableMap(copiedRegistries);
    }

    public record Tag(String identifier, List<Integer> entries) {
        public Tag {
            entries = List.copyOf(entries);
        }
    }
}
