package com.llamamc.llama.registry;

import com.llamamc.llamaapi.block.BlockProperty;
import com.llamamc.llamaapi.block.IBlockType;
import com.llamamc.llamaapi.registry.NamespacedKey;
import com.llamamc.llamaapi.world.BlockState;

import java.util.List;

record MinecraftBlockType(NamespacedKey key, BlockState defaultState,
                          List<BlockProperty> properties) implements IBlockType {
    MinecraftBlockType {
        properties = List.copyOf(properties);
    }
}
