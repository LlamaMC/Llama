package com.llamamc.llama.registry;

import com.llamamc.llama.network.minecraft.protocol.MinecraftProtocolVersion;
import com.llamamc.llamaapi.registry.NamespacedKey;
import com.llamamc.llamaapi.registry.minecraft.Blocks;
import com.llamamc.llamaapi.registry.minecraft.Items;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftRegistriesTest {
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
}
