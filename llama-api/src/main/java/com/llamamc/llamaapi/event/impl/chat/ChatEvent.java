package com.llamamc.llamaapi.event.impl.chat;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.event.ICancelableEvent;
import com.llamamc.llamaapi.player.IPlayer;

public final class ChatEvent implements ICancelableEvent {
    private final IPlayer player;
    private Component message;
    private boolean canceled;

    public ChatEvent(IPlayer player, Component message) {
        if (player == null) {
            throw new IllegalArgumentException("Chat player must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("Chat message must not be null");
        }
        this.player = player;
        this.message = message;
    }

    public IPlayer player() {
        return player;
    }

    public Component message() {
        return message;
    }

    public void setMessage(Component message) {
        if (message == null) {
            throw new IllegalArgumentException("Chat message must not be null");
        }
        this.message = message;
    }

    @Override
    public void cancel(boolean cancel) {
        this.canceled = cancel;
    }

    @Override
    public boolean canceled() {
        return canceled;
    }
}
