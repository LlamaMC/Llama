package com.llamamc.llama.example;

import com.llamamc.llamaapi.ILlamaRuntime;
import com.llamamc.llamaapi.di.annotation.Inject;
import com.llamamc.llamaapi.plugin.*;

@Plugin(id = "testplugin", name = "TestPlugin", version = "1.0.0", authors = {"ezTxmMC", "TntTastisch", "SyntaxJason"})
@Load(value = LoadPhase.PRE_WORLD)
public final class TestPlugin implements IPlugin {
    @Inject private ILlamaRuntime runtime;
    @Inject private IPluginContext context;

    @Override
    public void load() {
        /*
            Test test = new Test();
            Injector.inject(test);
         */
    }

    @Override
    public void enable() {

    }

    @Override
    public void disable() {
        this.runtime = null;
        this.context = null;
    }
}