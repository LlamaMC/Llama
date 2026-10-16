package com.llamamc.llama.plugin;

import java.nio.file.Path;

record PluginCandidate(Path jar, PluginDescriptor descriptor) {
}
