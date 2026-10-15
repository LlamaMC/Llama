package com.llamamc.llama.registry;

import com.llamamc.llamaapi.item.IItemType;
import com.llamamc.llamaapi.registry.NamespacedKey;

record MinecraftItemType(NamespacedKey key) implements IItemType {
}
