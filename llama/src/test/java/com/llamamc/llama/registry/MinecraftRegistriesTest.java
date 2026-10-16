package com.llamamc.llama.registry;

import com.llamamc.llama.network.minecraft.protocol.MinecraftProtocolVersion;
import com.llamamc.llamaapi.block.BlockProperty;
import com.llamamc.llamaapi.registry.NamespacedKey;
import com.llamamc.llamaapi.registry.minecraft.Blocks;
import com.llamamc.llamaapi.registry.minecraft.Items;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftRegistriesTest {
    private static final NamespacedKey OAK_SLAB = NamespacedKey.minecraft("oak_slab");

    @Test
    void exposesEveryReported26_3BlockAndItemType() {
        var registries = new MinecraftRegistries(MinecraftProtocolVersion.V26_3);

        assertEquals(1_286, registries.blocks().size());
        assertEquals(1_658, registries.items().size());
        assertTrue(registries.blocks().contains(NamespacedKey.minecraft("sulfur")));
        assertTrue(registries.items().contains(NamespacedKey.minecraft("diamond_pickaxe")));
    }

    @Test
    void keepsProtocolIdsOutsideThePublicBlockApi() {
        var registries = new MinecraftRegistries(MinecraftProtocolVersion.V26_3);
        var grass = registries.blocks().require("minecraft:grass_block");

        assertEquals("false", grass.defaultState().property("snowy"));
        assertEquals(9, registries.blockStateId(grass.defaultState()));
    }

    @Test
    void resolvesAProtocolIdForEveryReported26_3BlockState() {
        var registries = new MinecraftRegistries(MinecraftProtocolVersion.V26_3);
        var ids = new HashSet<Integer>();

        assertEquals(35_723, registries.blockStates().size());
        assertEquals(35_722, registries.maxBlockStateId());
        for (var state : registries.blockStates()) {
            var id = registries.blockStateId(state);
            assertTrue(id >= 0 && id <= registries.maxBlockStateId(), "ID out of range for " + state);
            assertTrue(ids.add(id), "Duplicate ID " + id + " for " + state);
        }
        assertEquals(35_723, ids.size());
    }

    @Test
    void exposesTypedKeysForEveryVanillaBlockAndItem() throws Exception {
        var registries = new MinecraftRegistries(MinecraftProtocolVersion.V26_3);

        assertEquals(1_286, Blocks.class.getFields().length);
        assertEquals(1_658, Items.class.getFields().length);
        assertEquals("minecraft:stone", Blocks.STONE.toString());
        assertEquals("minecraft:diamond_pickaxe", Items.DIAMOND_PICKAXE.toString());

        for (var field : Blocks.class.getFields()) {
            assertTrue(registries.blocks().contains((NamespacedKey) field.get(null)));
        }
        for (var field : Items.class.getFields()) {
            assertTrue(registries.items().contains((NamespacedKey) field.get(null)));
        }
    }

    @Test
    void groupsTheStatesOfOneKeyIntoOneBlockType() throws Exception {
        var loaded = MinecraftRegistries.parseBlocks(fixtureLines());

        assertEquals(3, loaded.types().size());
        assertEquals(9, loaded.stateIds().size());
        assertEquals(15_182, loaded.maxStateId());
        var slab = loaded.types().stream().filter(type -> type.key().equals(OAK_SLAB)).findFirst().orElseThrow();
        assertEquals(List.of(new BlockProperty("type"), new BlockProperty("waterlogged")), slab.properties());
        assertEquals(Map.of("type", "bottom", "waterlogged", "false"), slab.defaultState().properties());
        assertEquals(15_180, loaded.stateIds().get(slab.defaultState()));
        assertEquals(6, loaded.stateIds().keySet().stream().filter(state -> state.key().equals(OAK_SLAB)).count());
    }

    @Test
    void givesABlockWithoutPropertiesOneStateAndNoPropertyNames() throws Exception {
        var loaded = MinecraftRegistries.parseBlocks(fixtureLines());
        var air = loaded.types().stream()
                .filter(type -> type.key().equals(NamespacedKey.minecraft("air")))
                .findFirst()
                .orElseThrow();

        assertEquals(List.of(), air.properties());
        assertEquals(Map.of(), air.defaultState().properties());
        assertEquals(0, loaded.stateIds().get(air.defaultState()));
    }

    @Test
    void rejectsABlockWithoutADefaultState() {
        var lines = List.of("minecraft:stone\t1\t\tfalse");

        assertThrows(IllegalStateException.class, () -> MinecraftRegistries.parseBlocks(lines));
    }

    @Test
    void rejectsABlockWithTwoDefaultStates() {
        var lines = List.of("minecraft:stone\t1\t\ttrue", "minecraft:stone\t2\tvariant=a\ttrue");

        assertThrows(IllegalStateException.class, () -> MinecraftRegistries.parseBlocks(lines));
    }

    @Test
    void rejectsTwoStatesWithTheSameProtocolId() {
        var lines = List.of("minecraft:stone\t1\t\ttrue", "minecraft:dirt\t1\t\ttrue");

        assertThrows(IllegalStateException.class, () -> MinecraftRegistries.parseBlocks(lines));
    }

    private static List<String> fixtureLines() throws Exception {
        var resource = MinecraftRegistriesTest.class.getResource("/block-states/blocks.tsv");
        assertNotNull(resource);
        return Files.readAllLines(Path.of(resource.toURI()));
    }
}
