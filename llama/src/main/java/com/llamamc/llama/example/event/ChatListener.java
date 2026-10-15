package com.llamamc.llama.example.event;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.event.EventPriority;
import com.llamamc.llamaapi.event.IEventBus;
import com.llamamc.llamaapi.event.IRegistration;
import com.llamamc.llamaapi.event.impl.chat.ChatEvent;

public class ChatListener {

    public static IRegistration install(IEventBus events) {
        if (events == null) {
            throw new IllegalArgumentException("Event bus must not be null");
        }
        return events.on(ChatEvent.class, EventPriority.HIGHEST, ChatListener::format);
    }

    private static void format(ChatEvent event) {
        event.setMessage(Component.of("<gray><player> <dark_gray>» <gray><message>")
                .replace("<player>", event.player().name())
                .replace("<message>", event.message()));
    }
}
