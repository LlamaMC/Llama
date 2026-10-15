package com.llamamc.llamaapi.registry;

import com.llamamc.llamaapi.block.IBlockType;
import com.llamamc.llamaapi.item.IItemType;

public interface IRegistries {
    IRegistry<IBlockType> blocks();
    IRegistry<IItemType> items();
}
