package com.llamamc.llamaapi.event.impl.chat;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.event.ICancelableEvent;
import com.llamamc.llamaapi.player.IPlayer;

public class ChatEvent implements ICancelableEvent {
    private final IPlayer player;
    private Component message;
    private boolean canceled;

    public ChatEvent(IPlayer player) {
        this.player = player;
    }

    public void format(Component component) {

    }

    public void render(RenderCallback callback) {

    }

    @FunctionalInterface
    public interface RenderCallback {
        Component render(IPlayer player, Component message);
    }

    public void message(Component message) {
        this.message = message;
    }

    public IPlayer player() {
        return player;
    }

    public Component message() {
        return message;
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
