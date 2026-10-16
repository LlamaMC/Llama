package com.llamamc.llamaapi.event.impl.player;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.event.ICancelableEvent;

import java.util.UUID;

/**
 * Posted on the tick thread after login and configuration. The player is not registered yet, so the event carries
 * the identity instead of an {@code IPlayer}. A canceled event disconnects the player with the reason.
 */
public final class PlayerPreJoinEvent implements ICancelableEvent {
    private static final Component DEFAULT_REASON = Component.of("You cannot join this server.");

    private final UUID uniqueId;
    private final String name;
    private Component reason = DEFAULT_REASON;
    private boolean canceled;

    public PlayerPreJoinEvent(UUID uniqueId, String name) {
        if (uniqueId == null) {
            throw new IllegalArgumentException("Player UUID must not be null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Player name must not be null");
        }
        this.uniqueId = uniqueId;
        this.name = name;
    }

    public UUID uniqueId() {
        return uniqueId;
    }

    public String name() {
        return name;
    }

    public Component reason() {
        return reason;
    }

    public void setReason(Component reason) {
        if (reason == null) {
            throw new IllegalArgumentException("Denial reason must not be null");
        }
        this.reason = reason;
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
