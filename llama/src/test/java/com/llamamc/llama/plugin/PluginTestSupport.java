package com.llamamc.llama.plugin;

import com.llamamc.llamaapi.ILlama;
import com.llamamc.llamaapi.event.IEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared by the tests and the fixture plugins. The fixture jars do not contain this class, so the plugin loader
 * resolves it through its parent class loader, the same one that loaded the test.
 */
public final class PluginTestSupport {

    private static final List<String> EVENTS = new ArrayList<>();
    private static ILlama llama;

    private PluginTestSupport() {
    }

    public interface Greeting {
        String text();
    }

    public static final class Ping implements IEvent {
    }

    public static synchronized void record(String event) {
        EVENTS.add(event);
    }

    public static synchronized List<String> events() {
        return List.copyOf(EVENTS);
    }

    public static synchronized void receiveLlama(ILlama received) {
        llama = received;
    }

    public static synchronized ILlama llama() {
        return llama;
    }

    public static synchronized void clear() {
        EVENTS.clear();
        llama = null;
    }
}
