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

    LlamaFuture<Boolean> hasPermission(String permission);

    void kick(Component component);
    void ban(Component component);
    void sudo(String command);
    void sendActionBar(Component component);
    void sendMessage(Component component);
    void sendBossBar(IBossBar bossBar);
    void gameMode(GameMode gameMode);
    void skin(ISkin skin);

}