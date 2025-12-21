package com.llamamc.llama;

import com.llamamc.llama.console.Console;
import com.llamamc.llamaapi.ILlama;
import com.llamamc.llamaapi.ILlamaRuntime;
import com.llamamc.llamaapi.console.IConsole;
import com.llamamc.llamaapi.event.IEventBus;

public class Llama implements ILlama {
    private final ILlamaRuntime runtime;
    private final IConsole console;

    public Llama() {
        runtime = new LlamaRuntime();
        runtime.start();
        console = new Console();
        console.start();
    }

    @Override
    public IEventBus eventBus() {
        return null;
    }

    @Override
    public void shutdown() {
        console.exit();
        ((Thread) console).interrupt();
        runtime.shutdown();
    }

    static void main() {
        ILlama llama = new Llama();
        Runtime.getRuntime().addShutdownHook(new Thread(llama::shutdown));
    }
}
