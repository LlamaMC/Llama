package com.llamamc.llama.plugin;

/**
 * The state of one plugin jar. The id is the descriptor id, or the jar file name when the descriptor could not be read.
 * The message explains why a plugin is disabled, and is null otherwise.
 */
public record PluginStatus(String id, PluginState state, String message) {
}
