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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class MinecraftRegistries implements IRegistries {
    private final IRegistry<IBlockType> blocks;
    private final IRegistry<IItemType> items;
    private final Map<BlockState, Integer> blockStateIds;
    private final int maxBlockStateId;

    public MinecraftRegistries(MinecraftProtocolVersion version) {
        var directory = "/minecraft/" + version.nameValue() + "/";
        var loadedBlocks = parseBlocks(lines(directory + "blocks.tsv"));
        blocks = new ImmutableRegistry<>(loadedBlocks.types());
        blockStateIds = loadedBlocks.stateIds();
        maxBlockStateId = loadedBlocks.maxStateId();
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

    public Collection<BlockState> blockStates() {
        return blockStateIds.keySet();
    }

    public int maxBlockStateId() {
        return maxBlockStateId;
    }

    static LoadedBlocks parseBlocks(List<String> lines) {
        var rowsByKey = new LinkedHashMap<NamespacedKey, List<BlockRow>>();
        var stateIds = new LinkedHashMap<BlockState, Integer>();
        var usedIds = new HashSet<Integer>();
        var maxStateId = -1;
        for (var line : lines) {
            var row = BlockRow.parse(line);
            if (stateIds.put(row.state(), row.id()) != null) {
                throw new IllegalStateException("Duplicate block state " + row.state());
            }
            if (!usedIds.add(row.id())) {
                throw new IllegalStateException("Duplicate block state ID " + row.id());
            }
            rowsByKey.computeIfAbsent(row.key(), ignored -> new ArrayList<>()).add(row);
            maxStateId = Math.max(maxStateId, row.id());
        }

        var types = new ArrayList<IBlockType>();
        for (var entry : rowsByKey.entrySet()) {
            types.add(blockType(entry.getKey(), entry.getValue()));
        }
        return new LoadedBlocks(types, Collections.unmodifiableMap(stateIds), maxStateId);
    }

    private static MinecraftBlockType blockType(NamespacedKey key, List<BlockRow> rows) {
        var defaults = rows.stream().filter(BlockRow::isDefault).toList();
        if (defaults.size() != 1) {
            throw new IllegalStateException("Block " + key + " must have exactly one default state, found "
                    + defaults.size());
        }

        var names = new TreeSet<>(rows.get(0).state().properties().keySet());
        for (var row : rows) {
            if (!row.state().properties().keySet().equals(names)) {
                throw new IllegalStateException("Block " + key + " has states with different properties");
            }
        }

        var definitions = names.stream().map(BlockProperty::new).toList();
        return new MinecraftBlockType(key, defaults.get(0).state(), definitions);
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

    record LoadedBlocks(List<IBlockType> types, Map<BlockState, Integer> stateIds, int maxStateId) {
    }

    private record BlockRow(NamespacedKey key, int id, BlockState state, boolean isDefault) {
        static BlockRow parse(String line) {
            var columns = line.split("\\t", -1);
            if (columns.length != 4) {
                throw new IllegalStateException("Block state line must have four columns: " + line);
            }

            var key = NamespacedKey.parse(columns[0]);
            var isDefault = switch (columns[3]) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalStateException("Default flag must be true or false: " + line);
            };
            return new BlockRow(key, Integer.parseInt(columns[1]),
                    new BlockState(key, properties(columns[2])), isDefault);
        }
    }
}
