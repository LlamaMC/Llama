package com.llamamc.llamaapi.player;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.concurrent.LlamaFuture;
import com.llamamc.llamaapi.entity.IHumanEntity;

import java.util.UUID;

public interface IPlayer extends IHumanEntity {
    UUID uniqueId();
    String name();
    String clientBrand();

    LlamaFuture<PlayerSnapshot> snapshot();

    LlamaFuture<Boolean> kick(Component component);
    LlamaFuture<Boolean> ban(Component component);
    LlamaFuture<Boolean> sudo(String command);
    LlamaFuture<Boolean> hasPermission(String permission);
    LlamaFuture<Void> sendActionBar(Component component);
    LlamaFuture<Void> sendMessage(Component component);
    LlamaFuture<Void> sendBossBar(IBossBar bossBar);
    LlamaFuture<Void> gameMode(GameMode gameMode);
    LlamaFuture<Void> skin(ISkin skin);

}
