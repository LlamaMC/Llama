package com.llamamc.llama.example;

import com.llamamc.llamaapi.di.annotation.Inject;
import com.llamamc.llamaapi.plugin.Depend;
import com.llamamc.llamaapi.plugin.DependType;
import com.llamamc.llamaapi.plugin.IPluginContext;

@Depend(dependType = DependType.SOFT, plugin = {"Essentials", "LuckPerms"})
public class Test {
    @Inject
    private IPluginContext context;
}
