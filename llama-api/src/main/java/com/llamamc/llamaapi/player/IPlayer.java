package com.llamamc.llamaapi.player;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.concurrent.LlamaFuture;
import com.llamamc.llamaapi.entity.IHumanEntity;

import java.util.UUID;

public interface IPlayer extends IHumanEntity {
    UUID uniqueId();
    String name();

    /** The {@code minecraft:brand} value the client sent during configuration, or "unknown" when it sent none. */
    String clientBrand();

    PlayerSnapshot snapshot();

    LlamaFuture<Boolean> hasPermission(String permission);

    /** Sends the disconnect with the reason, then closes the connection. */
    void kick(Component reason);

    void sendMessage(Component message);

}
