package com.llamamc.llama.registry;

import com.llamamc.llama.network.minecraft.protocol.MinecraftProtocolVersion;
import com.llamamc.llamaapi.block.BlockProperty;
import com.llamamc.llamaapi.block.IBlockType;
import com.llamamc.llamaapi.item.IItemType;
import com.llamamc.llamaapi.registry.IRegistries;
import com.llamamc.llamaapi.registry.IRegistry;
import com.llamamc.llamaapi.registry.NamespacedKey;
import com.llamamc.llamaapi.world.BlockState;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MinecraftRegistries implements IRegistries {
    private final IRegistry<IBlockType> blocks;
    private final IRegistry<IItemType> items;
    private final Map<BlockState, Integer> blockStateIds;

    public MinecraftRegistries(MinecraftProtocolVersion version) {
        var directory = "/minecraft/" + version.nameValue() + "/";
        var loadedBlocks = loadBlocks(directory + "blocks.tsv");
        blocks = new ImmutableRegistry<>(loadedBlocks.types());
        blockStateIds = Map.copyOf(loadedBlocks.stateIds());
        items = new ImmutableRegistry<>(loadItems(directory + "items.tsv"));
    }

    @Override
    public IRegistry<IBlockType> blocks() {
        return blocks;
    }

    @Override
    public IRegistry<IItemType> items() {
        return items;
    }

    public int blockStateId(BlockState state) {
        var id = blockStateIds.get(state);
        if (id != null) {
            return id;
        }
        throw new IllegalArgumentException("Block state is not registered for this protocol: " + state);
    }

    private static LoadedBlocks loadBlocks(String resource) {
        var types = new ArrayList<IBlockType>();
        var stateIds = new LinkedHashMap<BlockState, Integer>();
        for (var line : lines(resource)) {
            var columns = line.split("\\t", -1);
            var key = NamespacedKey.parse(columns[0]);
            var properties = properties(columns.length > 2 ? columns[2] : "");
            var state = new BlockState(key, properties);
            var definitions = properties.entrySet().stream()
                    .map(entry -> new BlockProperty(entry.getKey()))
                    .toList();
            types.add(new MinecraftBlockType(key, state, definitions));
            stateIds.put(state, Integer.parseInt(columns[1]));
        }
        return new LoadedBlocks(types, stateIds);
    }

    private static List<IItemType> loadItems(String resource) {
        var types = new ArrayList<IItemType>();
        for (var line : lines(resource)) {
            var columns = line.split("\\t", -1);
            types.add(new MinecraftItemType(NamespacedKey.parse(columns[0])));
        }
        return types;
    }

    private static Map<String, String> properties(String encoded) {
        if (encoded.isBlank()) {
            return Map.of();
        }
        var properties = new LinkedHashMap<String, String>();
        for (var property : encoded.split(",")) {
            var separator = property.indexOf('=');
            properties.put(property.substring(0, separator), property.substring(separator + 1));
        }
        return properties;
    }

    private static List<String> lines(String resource) {
        var stream = MinecraftRegistries.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IllegalStateException("Missing Minecraft registry resource: " + resource);
        }
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.isBlank()).toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private record LoadedBlocks(List<IBlockType> types, Map<BlockState, Integer> stateIds) {
    }
}
