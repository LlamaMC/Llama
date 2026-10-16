package com.llamamc.llama.player;

import com.llamamc.llama.command.PlayerSender;
import com.llamamc.llamaapi.command.ICommandRegistry;
import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.event.IEventBus;
import com.llamamc.llamaapi.event.impl.chat.ChatEvent;
import com.llamamc.llamaapi.event.impl.player.PlayerJoinEvent;
import com.llamamc.llamaapi.event.impl.player.PlayerPreJoinEvent;
import com.llamamc.llamaapi.event.impl.player.PlayerQuitEvent;
import com.llamamc.llamaapi.network.protocol.IPacket;
import com.llamamc.llamaapi.permission.IPermissionService;
import com.llamamc.llamaapi.player.IPlayerRegistry;
import com.llamamc.llamaapi.scheduler.IScheduler;

import java.util.List;
import java.util.UUID;

/**
 * Player state changes. The network threads call the {@code request} methods, which queue the work on the tick thread
 * through the scheduler. The private methods run only on the tick thread.
 */
public final class PlayerLifecycle {
    private static final String ANOTHER_LOCATION = "You logged in from another location.";
    private static final String UNKNOWN_COMMAND = "Unknown command.";

    private final IScheduler scheduler;
    private final IEventBus events;
    private final ICommandRegistry commands;
    private final IPermissionService permissions;
    private final PlayerRegistry registry = new PlayerRegistry();

    public PlayerLifecycle(IScheduler scheduler, IEventBus events, ICommandRegistry commands,
                           IPermissionService permissions) {
        if (scheduler == null) {
            throw new IllegalArgumentException("Scheduler must not be null");
        }
        if (events == null) {
            throw new IllegalArgumentException("Event bus must not be null");
        }
        if (commands == null) {
            throw new IllegalArgumentException("Command registry must not be null");
        }
        if (permissions == null) {
            throw new IllegalArgumentException("Permission service must not be null");
        }

        this.scheduler = scheduler;
        this.events = events;
        this.commands = commands;
        this.permissions = permissions;
    }

    public IPlayerRegistry registry() {
        return registry;
    }

    public NetworkPlayer createPlayer(UUID uniqueId, String name, String clientBrand, IPlayerConnection connection) {
        return new NetworkPlayer(uniqueId, name, clientBrand, connection, permissions);
    }

    public void requestJoin(NetworkPlayer player, List<IPacket> joinPackets) {
        if (player == null) {
            throw new IllegalArgumentException("Player must not be null");
        }
        if (joinPackets == null) {
            throw new IllegalArgumentException("Join packets must not be null");
        }
        scheduler.runOnTick(() -> admit(player, joinPackets));
    }

    public void requestQuit(NetworkPlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("Player must not be null");
        }
        scheduler.runOnTick(() -> quit(player));
    }

    public void requestChat(NetworkPlayer player, String message) {
        if (player == null) {
            throw new IllegalArgumentException("Player must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException("Chat message must not be null");
        }
        scheduler.runOnTick(() -> chat(player, message));
    }

    public void requestCommand(NetworkPlayer player, String line) {
        if (player == null) {
            throw new IllegalArgumentException("Player must not be null");
        }
        if (line == null) {
            throw new IllegalArgumentException("Command line must not be null");
        }
        scheduler.runOnTick(() -> command(player, line));
    }

    private void admit(NetworkPlayer player, List<IPacket> joinPackets) {
        var preJoin = new PlayerPreJoinEvent(player.uniqueId(), player.name());
        events.post(preJoin);
        if (preJoin.canceled()) {
            player.connection().denyJoin(preJoin.reason());
            return;
        }
        // The connection closed while the event handlers ran. Nothing is registered for a closed connection.
        if (player.isQuitting()) {
            return;
        }

        replaceExisting(player.uniqueId());
        registry.register(player);
        player.connection().join(joinPackets, () -> scheduler.runOnTick(() -> joined(player)));
    }

    // A UUID has one session. The old session quits before the new one is registered, so its quit event comes first.
    private void replaceExisting(UUID uniqueId) {
        var previous = registry.session(uniqueId);
        if (previous.isEmpty()) {
            return;
        }

        var old = previous.get();
        old.kick(Component.of(ANOTHER_LOCATION));
        quit(old);
    }

    private void joined(NetworkPlayer player) {
        if (player.isQuitting()) {
            return;
        }
        events.post(new PlayerJoinEvent(player));
    }

    // Both the disconnect and the socket close lead here. Only the first call does anything.
    private void quit(NetworkPlayer player) {
        if (!player.beginQuit()) {
            return;
        }
        // A player that never registered quits silently, so PlayerQuitEvent fires only for registered players.
        if (!registry.remove(player)) {
            return;
        }
        events.post(new PlayerQuitEvent(player));
    }

    private void chat(NetworkPlayer player, String message) {
        if (player.isQuitting()) {
            return;
        }

        var event = new ChatEvent(player, Component.of(message));
        events.post(event);
        if (event.canceled()) {
            return;
        }

        var line = Component.of("<" + player.name() + "> ").append(event.message());
        for (var online : registry.online()) {
            online.sendMessage(line);
        }
    }

    private void command(NetworkPlayer player, String line) {
        if (player.isQuitting()) {
            return;
        }
        if (commands.dispatch(new PlayerSender(player, permissions), line)) {
            return;
        }
        player.sendMessage(Component.of(UNKNOWN_COMMAND));
    }
}
