package com.llamamc.llama.network.minecraft;

import com.llamamc.llama.command.CommandRegistry;
import com.llamamc.llama.event.EventBus;
import com.llamamc.llama.network.minecraft.protocol.Minecraft26_3Protocol;
import com.llamamc.llama.player.PlayerLifecycle;
import com.llamamc.llama.player.TestPlayers;
import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.event.impl.player.PlayerJoinEvent;
import com.llamamc.llamaapi.event.impl.player.PlayerPreJoinEvent;
import com.llamamc.llamaapi.event.impl.player.PlayerQuitEvent;
import com.llamamc.llamaapi.network.minecraft.packet.common.DisconnectPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.ConfigurationAcknowledgedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.CustomPayloadPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.KnownPack;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.KnownPacksSelectedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.handshake.HandshakePacket;
import com.llamamc.llamaapi.network.minecraft.packet.login.LoginAcknowledgedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.login.LoginStartPacket;
import com.llamamc.llamaapi.network.protocol.DefaultProtocolState;
import com.llamamc.llamaapi.network.protocol.IPacket;
import com.llamamc.llamaapi.network.protocol.PacketFlow;
import com.llamamc.llamaapi.network.protocol.PacketRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class MinecraftConnectionHandlerTest {
    private static final KnownPack CORE_PACK = new KnownPack("minecraft", "core", "26.3");

    private final TestPlayers.Scheduler scheduler = new TestPlayers.Scheduler();
    private final EventBus events = new EventBus();
    private final TestPlayers.Permissions permissions = new TestPlayers.Permissions();
    private final PlayerLifecycle lifecycle =
            new PlayerLifecycle(scheduler, events, new CommandRegistry(permissions), permissions);
    private final PacketRegistry packets = Minecraft26_3Protocol.createRegistry();
    private final EmbeddedChannel channel = new EmbeddedChannel(new MinecraftConnectionHandler(
            new ServerStatus("Llama test server", 10), packets, null, lifecycle));

    @Test
    void closingTheConnectionQuitsThePlayerExactlyOnce() {
        var joins = new ArrayList<PlayerJoinEvent>();
        var quits = new ArrayList<PlayerQuitEvent>();
        events.on(PlayerJoinEvent.class, joins::add);
        events.on(PlayerQuitEvent.class, quits::add);
        logInAndAcknowledgeConfiguration("Alice");
        scheduler.runQueued();
        assertEquals(1, joins.size());
        assertEquals(1, lifecycle.registry().online().size());

        channel.close();
        scheduler.runQueued();
        assertEquals(1, quits.size());
        assertEquals("Alice", quits.getFirst().player().name());

        channel.close();
        channel.pipeline().fireChannelInactive();
        scheduler.runQueued();
        assertEquals(1, quits.size());
        assertTrue(lifecycle.registry().online().isEmpty());
    }

    @Test
    void aCanceledPreJoinEventDisconnectsBeforePlayAndRegistersNothing() {
        var joins = new ArrayList<PlayerJoinEvent>();
        events.on(PlayerJoinEvent.class, joins::add);
        events.on(PlayerPreJoinEvent.class, event -> {
            event.cancel(true);
            event.setReason(Component.of("Maintenance"));
        });
        logInAndAcknowledgeConfiguration("Alice");
        scheduler.runQueued();

        assertFalse(channel.isOpen());
        assertTrue(joins.isEmpty());
        assertTrue(lifecycle.registry().online().isEmpty());
        assertEquals(new DisconnectPacket(Component.of("Maintenance")),
                lastOutbound(DefaultProtocolState.CONFIGURATION));
    }

    private void logInAndAcknowledgeConfiguration(String username) {
        send(DefaultProtocolState.HANDSHAKE,
                new HandshakePacket(ServerStatus.PROTOCOL_VERSION, "localhost", 25565, HandshakePacket.Intent.LOGIN));
        send(DefaultProtocolState.LOGIN, new LoginStartPacket(username, UUID.randomUUID()));
        send(DefaultProtocolState.LOGIN, new LoginAcknowledgedPacket());
        send(DefaultProtocolState.CONFIGURATION, new CustomPayloadPacket("minecraft:brand", brand("vanilla")));
        send(DefaultProtocolState.CONFIGURATION, new KnownPacksSelectedPacket(List.of(CORE_PACK)));
        send(DefaultProtocolState.CONFIGURATION, new ConfigurationAcknowledgedPacket());
    }

    private void send(DefaultProtocolState state, IPacket packet) {
        var buffer = Unpooled.buffer();
        packets.encode(state, PacketFlow.SERVERBOUND, buffer, packet);
        channel.writeInbound(buffer);
    }

    private IPacket lastOutbound(DefaultProtocolState state) {
        ByteBuf last = null;
        ByteBuf message;
        while ((message = channel.readOutbound()) != null) {
            last = message;
        }
        assertNotNull(last);
        return packets.decode(state, PacketFlow.CLIENTBOUND, last);
    }

    private static byte[] brand(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        var buffer = Unpooled.buffer();
        VarInts.write(buffer, bytes.length);
        buffer.writeBytes(bytes);
        return ByteBufUtil.getBytes(buffer);
    }
}
