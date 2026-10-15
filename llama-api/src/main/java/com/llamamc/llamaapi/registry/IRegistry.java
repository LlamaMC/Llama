package com.llamamc.llamaapi.registry;

import java.util.Collection;
import java.util.Optional;

public interface IRegistry<T extends IRegistryEntry> {
    Optional<T> find(NamespacedKey key);
    T require(NamespacedKey key);
    Collection<T> values();

    default Optional<T> find(String key) {
        return NamespacedKey.tryParse(key).flatMap(this::find);
    }

    default T require(String key) {
        return require(NamespacedKey.parse(key));
    }

    default boolean contains(NamespacedKey key) {
        return find(key).isPresent();
    }

    default int size() {
        return values().size();
    }
}
