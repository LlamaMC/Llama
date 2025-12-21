package com.llamamc.llamaapi.plugin;

public interface IPlugin {
    default void load() {}
    default void enable() {}
    default void disable() {}
}
