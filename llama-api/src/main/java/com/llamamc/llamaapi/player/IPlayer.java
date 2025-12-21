package com.llamamc.llamaapi.player;

import com.llamamc.llamaapi.block.IBlock;
import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.entity.IEntity;
import com.llamamc.llamaapi.potion.IPotionEffect;
import com.llamamc.llamaapi.world.ILocation;
import com.llamamc.llamaapi.world.IWorld;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.UUID;

public interface IPlayer {
    UUID uniqueId();
    String name();
    String clientBrand();
    long ping();
    double health();
    GameMode gameMode();
    ILocation location();
    IWorld world();
    IBlock block();
    IBlock blockLookingAt();
    IEntity entityLookingAt();
    Collection<IPotionEffect> activePotionEffects();
    ISkin skin();
    boolean onGround();
    boolean flying();
    boolean sneaking();
    boolean sprinting();
    boolean transferred();
    boolean kick(Component component);
    boolean ban(Component component);
    boolean sudo(String command);
    boolean hasPermission(String permission);
    void sendActionBar(Component component);
    void sendMessage(Component component);
    void sendBossBar(IBossBar bossBar);
    void gameMode(GameMode gameMode);
    void skin(ISkin skin);
    InetSocketAddress getAddress();
}
