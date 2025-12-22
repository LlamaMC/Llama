package com.llamamc.llama.example.event;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.component.Placeholder;
import com.llamamc.llamaapi.event.Event;
import com.llamamc.llamaapi.event.EventPriority;
import com.llamamc.llamaapi.event.impl.chat.ChatEvent;
import com.llamamc.llamaapi.player.IPlayer;

public class ChatListener {

    @Event(priority = EventPriority.HIGHEST, async = true)
    public void handleChat(ChatEvent event) {
        event.render((IPlayer player, Component message) ->
                Component.parse("<gray><player> <dark_gray>» <gray><message>")
                        .resolve(Placeholder.parse("player", player.name()),
                                Placeholder.parse("message", message)).coloring());
    }
}
