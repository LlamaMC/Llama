package com.llamamc.llamaapi.block;

import com.llamamc.llamaapi.registry.IRegistryEntry;
import com.llamamc.llamaapi.world.BlockState;

import java.util.List;

public interface IBlockType extends IRegistryEntry {
    BlockState defaultState();
    List<BlockProperty> properties();

    default boolean isAir() {
        return key().toString().equals("minecraft:air");
    }
}
