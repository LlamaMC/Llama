package com.llamamc.llama.command;

import com.llamamc.llama.player.NetworkPlayer;
import com.llamamc.llama.player.TestPlayers;
import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.network.minecraft.packet.play.SystemChatPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class PlayerSenderTest {
    private static final String NODE = "llama.test.node";

    private final UUID uniqueId = UUID.nameUUIDFromBytes("Alice".getBytes(StandardCharsets.UTF_8));
    private final TestPlayers.Connection connection = new TestPlayers.Connection();
    private final TestPlayers.Permissions permissions = new TestPlayers.Permissions();
    private final NetworkPlayer player = new NetworkPlayer(uniqueId, "Alice", "vanilla", connection, permissions);
    private final PlayerSender sender = new PlayerSender(player, permissions);

    @Test
    void permissionsAreCheckedForThePlayersUuid() {
        permissions.grant(uniqueId, NODE);
        var stranger = new NetworkPlayer(UUID.randomUUID(), "Bob", "vanilla", new TestPlayers.Connection(),
                permissions);

        assertTrue(sender.hasPermission(NODE));
        assertFalse(sender.hasPermission("llama.test.other"));
        assertFalse(new PlayerSender(stranger, permissions).hasPermission(NODE));
    }

    @Test
    void messagesReachTheConnectionAsSystemChatWithoutOverlay() {
        sender.sendMessage(Component.of("Hello"));

        assertEquals(List.of(new SystemChatPacket(Component.of("Hello"), false)), connection.sent());
    }

    @Test
    void theSenderNameIsThePlayerName() {
        assertEquals("Alice", sender.name());
    }
}
