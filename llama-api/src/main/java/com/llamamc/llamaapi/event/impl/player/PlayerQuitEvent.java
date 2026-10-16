package com.llamamc.llamaapi.event.impl.player;

import com.llamamc.llamaapi.event.IEvent;
import com.llamamc.llamaapi.player.IPlayer;

public record PlayerQuitEvent(IPlayer player) implements IEvent {}
