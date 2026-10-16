package com.llamamc.llama.player;

import com.llamamc.llama.command.CommandRegistry;
import com.llamamc.llama.event.EventBus;
import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.event.impl.chat.ChatEvent;
import com.llamamc.llamaapi.event.impl.player.PlayerQuitEvent;
import com.llamamc.llamaapi.network.minecraft.packet.play.SystemChatPacket;
import com.llamamc.llamaapi.network.protocol.IPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class PlayerLifecycleTest {
    private final TestPlayers.Scheduler scheduler = new TestPlayers.Scheduler();
    private final EventBus events = new EventBus();
    private final TestPlayers.Permissions permissions = new TestPlayers.Permissions();
    private final PlayerLifecycle lifecycle =
            new PlayerLifecycle(scheduler, events, new CommandRegistry(permissions), permissions);

    @Test
    void chatReachesEveryOnlinePlayerInThePlainFormat() {
        var alice = new TestPlayers.Connection();
        var bob = new TestPlayers.Connection();
        var sender = join("Alice", alice);
        join("Bob", bob);

        lifecycle.requestChat(sender, "hello there");
        scheduler.runQueued();

        var line = List.of(new SystemChatPacket(Component.of("<Alice> hello there"), false));
        assertEquals(line, alice.sent());
        assertEquals(line, bob.sent());
    }

    @Test
    void aCanceledChatEventBroadcastsNothing() {
        events.on(ChatEvent.class, event -> event.cancel(true));
        var alice = new TestPlayers.Connection();
        var bob = new TestPlayers.Connection();
        var sender = join("Alice", alice);
        join("Bob", bob);

        lifecycle.requestChat(sender, "hello there");
        scheduler.runQueued();

        assertTrue(alice.sent().isEmpty());
        assertTrue(bob.sent().isEmpty());
    }

    @Test
    void aSecondLoginWithTheSameUuidKicksTheOldSessionAndQuitsItFirst() {
        var quits = new ArrayList<PlayerQuitEvent>();
        events.on(PlayerQuitEvent.class, quits::add);
        var firstConnection = new TestPlayers.Connection();
        var secondConnection = new TestPlayers.Connection();
        var first = join("Alice", firstConnection);

        var second = lifecycle.createPlayer(first.uniqueId(), "Alice", "vanilla", secondConnection);
        lifecycle.requestJoin(second, List.of());
        scheduler.runQueued();

        assertEquals(Component.of("You logged in from another location."), firstConnection.disconnectReason());
        assertEquals(1, quits.size());
        assertSame(first, quits.getFirst().player());
        assertTrue(secondConnection.joined());
        assertSame(second, lifecycle.registry().byUuid(first.uniqueId()).orElseThrow());

        // The old socket closes after the kick. Its quit is already done, so no second event follows.
        lifecycle.requestQuit(first);
        scheduler.runQueued();
        assertEquals(1, quits.size());
    }

    @Test
    void shutdownDisconnectsEveryPlayerAndFiresOneQuitForEach() {
        var quits = new ArrayList<PlayerQuitEvent>();
        events.on(PlayerQuitEvent.class, quits::add);
        var aliceConnection = new TestPlayers.Connection();
        var bobConnection = new TestPlayers.Connection();
        var alice = join("Alice", aliceConnection);
        var bob = join("Bob", bobConnection);

        var done = lifecycle.disconnectAll();
        scheduler.runQueued();

        assertTrue(done.isDone());
        assertEquals(Component.of("Server closed"), aliceConnection.disconnectReason());
        assertEquals(Component.of("Server closed"), bobConnection.disconnectReason());
        assertEquals(2, quits.size());
        assertTrue(quits.stream().map(PlayerQuitEvent::player).toList().containsAll(List.of(alice, bob)));
        assertTrue(lifecycle.registry().online().isEmpty());

        // The sockets close after the kick. Their quits are already done, so no second event follows.
        lifecycle.requestQuit(alice);
        lifecycle.requestQuit(bob);
        scheduler.runQueued();
        assertEquals(2, quits.size());
    }

    private NetworkPlayer join(String name, TestPlayers.Connection connection) {
        var uniqueId = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        var player = lifecycle.createPlayer(uniqueId, name, "vanilla", connection);
        List<IPacket> joinPackets = List.of();
        lifecycle.requestJoin(player, joinPackets);
        scheduler.runQueued();
        return player;
    }
}
