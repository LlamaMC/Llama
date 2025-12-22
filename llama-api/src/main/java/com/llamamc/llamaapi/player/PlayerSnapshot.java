package com.llamamc.llamaapi.player;

import com.llamamc.llamaapi.potion.IPotionEffect;
import com.llamamc.llamaapi.world.ILocation;
import com.llamamc.llamaapi.world.IWorld;

import java.net.InetSocketAddress;
import java.util.Collection;

public record PlayerSnapshot(
        long ping,
        double health,
        GameMode gameMode,
        ILocation location,
        IWorld world,
        boolean onGround,
        boolean flying,
        boolean sneaking,
        boolean sprinting,
        boolean transferred,
        Collection<IPotionEffect> activePotionEffects,
        ISkin skin,
        InetSocketAddress address
) {}
