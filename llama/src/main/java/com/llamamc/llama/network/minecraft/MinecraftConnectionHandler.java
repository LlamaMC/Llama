package com.llamamc.llama.network.minecraft;

import com.google.gson.JsonObject;
import com.llamamc.llama.network.minecraft.protocol.Minecraft26_3Protocol;
import com.llamamc.llama.network.minecraft.protocol.Minecraft26_3Registries;
import com.llamamc.llamaapi.network.minecraft.packet.common.KeepAlivePacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.ClientInformationPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.ConfigurationAcknowledgedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.CustomPayloadPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.FinishConfigurationPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.KnownPack;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.KnownPacksSelectedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.SelectKnownPacksPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.UpdateEnabledFeaturesPacket;
import com.llamamc.llamaapi.network.minecraft.packet.handshake.HandshakePacket;
import com.llamamc.llamaapi.network.minecraft.packet.login.LoginAcknowledgedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.login.LoginFinishedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.login.LoginStartPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.AcceptTeleportationPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ChunkBatchReceivedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ChunkBatchFinishedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ChunkBatchStartPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ForgetLevelChunkPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.GameEvent;
import com.llamamc.llamaapi.network.minecraft.packet.play.GameEventPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.LevelChunkWithLightPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.MovePlayerPositionPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.MovePlayerPositionRotationPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.MovePlayerRotationPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.MovePlayerStatusPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.PlayLoginPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.PlayerLoadedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.PlayerPositionPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.SetChunkCacheCenterPacket;
import com.llamamc.llamaapi.network.minecraft.packet.status.PingRequestPacket;
import com.llamamc.llamaapi.network.minecraft.packet.status.PongResponsePacket;
import com.llamamc.llamaapi.network.minecraft.packet.status.StatusRequestPacket;
import com.llamamc.llamaapi.network.minecraft.packet.status.StatusResponsePacket;
import com.llamamc.llamaapi.network.protocol.DefaultProtocolState;
import com.llamamc.llamaapi.network.protocol.IPacket;
import com.llamamc.llamaapi.network.protocol.PacketFlow;
import com.llamamc.llamaapi.network.protocol.PacketRegistry;
import com.llamamc.llamaapi.network.protocol.UnknownPacketException;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;
import com.llamamc.llamaapi.world.IWorld;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class MinecraftConnectionHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private static final Logger LOGGER = LoggerFactory.getLogger(MinecraftConnectionHandler.class);
    private static final AtomicInteger NEXT_PLAYER_ID = new AtomicInteger(1);
    private static final KnownPack CORE_PACK = new KnownPack("minecraft", "core", "26.3");
    private static final int VIEW_DISTANCE = 2;
    private static final int TRACKING_RADIUS = VIEW_DISTANCE + 1;
    private static final int INITIAL_CHUNKS_PER_BATCH = 9;
    private static final int MAX_CHUNKS_PER_BATCH = 64;
    private static final double MAXIMUM_POSITION = 30_000_000.0;
    private static final Executor CHUNK_REQUEST_EXECUTOR = task -> Thread.ofVirtual()
            .name("Llama-Chunk-Request")
            .start(task);

    private final ServerStatus status;
    private final PacketRegistry registry;
    private final IWorld world;

    private DefaultProtocolState state = DefaultProtocolState.HANDSHAKE;
    private ConnectionStep step = ConnectionStep.HANDSHAKE;
    private UUID profileId;
    private ScheduledFuture<?> keepAliveTask;
    private final Set<ChunkPosition> streamedChunks = new HashSet<>();
    private final ArrayDeque<IChunk> readyChunks = new ArrayDeque<>();
    private Set<ChunkPosition> desiredChunks = Set.of();
    private ChunkPosition chunkCenter;
    private long chunkStreamRevision;
    private int chunksPerBatch = INITIAL_CHUNKS_PER_BATCH;
    private boolean batchInFlight;
    private boolean spawnPositionPending;

    MinecraftConnectionHandler(ServerStatus status, PacketRegistry registry, IWorld world) {
        this.status = status;
        this.registry = registry;
        this.world = world;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, ByteBuf packet) {
        if (state == DefaultProtocolState.PLAY) {
            handlePlayPacket(context, packet);
            return;
        }

        var decoded = registry.decode(state, PacketFlow.SERVERBOUND, packet);

        switch (decoded) {
            case HandshakePacket handshake -> handleHandshake(context, handshake);
            case StatusRequestPacket ignored -> sendStatus(context);
            case PingRequestPacket ping -> sendPong(context, ping.payload());
            case LoginStartPacket login -> handleLoginStart(context, login);
            case LoginAcknowledgedPacket ignored -> beginConfiguration(context);
            case ClientInformationPacket ignored -> ignoreConfigurationMetadata(context);
            case CustomPayloadPacket ignored -> ignoreConfigurationMetadata(context);
            case KnownPacksSelectedPacket selected -> synchronizeRegistries(context, selected);
            case ConfigurationAcknowledgedPacket ignored -> beginPlay(context);
            default -> close(context);
        }
    }

    private void handlePlayPacket(ChannelHandlerContext context, ByteBuf packet) {
        IPacket decoded;
        try {
            decoded = registry.decode(state, PacketFlow.SERVERBOUND, packet);
        } catch (UnknownPacketException ignored) {
            return;
        }

        switch (decoded) {
            case MovePlayerPositionPacket movement -> moveChunkCenter(context, movement.x(), movement.z());
            case MovePlayerPositionRotationPacket movement -> moveChunkCenter(context, movement.x(), movement.z());
            case ChunkBatchReceivedPacket acknowledgement -> acknowledgeChunkBatch(context, acknowledgement);
            case AcceptTeleportationPacket ignored -> { }
            case PlayerLoadedPacket ignored -> { }
            case MovePlayerRotationPacket ignored -> { }
            case MovePlayerStatusPacket ignored -> { }
            case KeepAlivePacket ignored -> { }
            default -> { }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable error) {
        LOGGER.warn("Minecraft protocol error from {} in {}", context.channel().remoteAddress(), step, error);
        close(context);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        if (keepAliveTask != null) {
            keepAliveTask.cancel(false);
        }

        super.channelInactive(context);
    }

    private void handleHandshake(ChannelHandlerContext context, HandshakePacket handshake) {
        if (step != ConnectionStep.HANDSHAKE) {
            close(context);
            return;
        }
        if (handshake.intent() == HandshakePacket.Intent.STATUS) {
            state = DefaultProtocolState.STATUS;
            step = ConnectionStep.STATUS;
            return;
        }
        if (handshake.protocolVersion() != Minecraft26_3Protocol.VERSION) {
            LOGGER.info("Rejected protocol {} from {}", handshake.protocolVersion(), context.channel().remoteAddress());
            close(context);
            return;
        }

        state = DefaultProtocolState.LOGIN;
        step = ConnectionStep.LOGIN_START;
    }

    private void handleLoginStart(ChannelHandlerContext context, LoginStartPacket login) {
        if (step != ConnectionStep.LOGIN_START) {
            close(context);
            return;
        }

        profileId = offlineProfileId(login.username());
        step = ConnectionStep.LOGIN_ACKNOWLEDGEMENT;
        sendAndFlush(context, DefaultProtocolState.LOGIN,
                new LoginFinishedPacket(profileId, login.username(), List.of(), UUID.randomUUID()));
    }

    private void beginConfiguration(ChannelHandlerContext context) {
        if (step != ConnectionStep.LOGIN_ACKNOWLEDGEMENT) {
            close(context);
            return;
        }

        state = DefaultProtocolState.CONFIGURATION;
        step = ConnectionStep.KNOWN_PACKS;
        send(context, state, new UpdateEnabledFeaturesPacket(Set.of("minecraft:vanilla")));
        send(context, state, new SelectKnownPacksPacket(List.of(CORE_PACK)));
        context.flush();
    }

    private void ignoreConfigurationMetadata(ChannelHandlerContext context) {
        if (state == DefaultProtocolState.CONFIGURATION) {
            return;
        }

        close(context);
    }

    private void synchronizeRegistries(ChannelHandlerContext context, KnownPacksSelectedPacket selected) {
        if (step != ConnectionStep.KNOWN_PACKS) {
            close(context);
            return;
        }
        if (!selected.packs().equals(List.of(CORE_PACK))) {
            LOGGER.info("Client {} does not support the 26.3 core pack", context.channel().remoteAddress());
            close(context);
            return;
        }

        step = ConnectionStep.CONFIGURATION_ACKNOWLEDGEMENT;

        for (var packet : Minecraft26_3Registries.packets()) {
            send(context, state, packet);
        }

        send(context, state, Minecraft26_3Registries.tags());
        send(context, state, new FinishConfigurationPacket());
        context.flush();
    }

    private void beginPlay(ChannelHandlerContext context) {
        if (step != ConnectionStep.CONFIGURATION_ACKNOWLEDGEMENT || profileId == null) {
            close(context);
            return;
        }

        state = DefaultProtocolState.PLAY;
        step = ConnectionStep.PLAY;
        send(context, state, new PlayLoginPacket(
                NEXT_PLAYER_ID.getAndIncrement(), status.maxPlayers(), VIEW_DISTANCE, VIEW_DISTANCE));
        if (world == null) {
            send(context, state, new PlayerPositionPacket(0, 0.5, 512.0, 0.5, 0.0F, 0.0F));
        } else {
            send(context, state, new GameEventPacket(GameEvent.LEVEL_CHUNKS_LOAD_START));
        }
        context.flush();
        if (world != null) {
            spawnPositionPending = true;
            updateChunkStream(context, new ChunkPosition(0, 0));
        }
        keepAliveTask = context.executor().scheduleAtFixedRate(
                () -> sendKeepAlive(context), 10, 10, TimeUnit.SECONDS);
        LOGGER.info("{} joined Llama as {}", context.channel().remoteAddress(), profileId);
    }

    private void moveChunkCenter(ChannelHandlerContext context, double x, double z) {
        if (world == null) {
            return;
        }
        if (!validPosition(x) || !validPosition(z)) {
            close(context);
            return;
        }

        var center = new ChunkPosition(Math.floorDiv((int) Math.floor(x), 16),
                Math.floorDiv((int) Math.floor(z), 16));
        if (center.equals(chunkCenter)) {
            return;
        }

        updateChunkStream(context, center);
    }

    private void updateChunkStream(ChannelHandlerContext context, ChunkPosition center) {
        chunkCenter = center;
        desiredChunks = trackingChunks(center);
        var revision = ++chunkStreamRevision;
        readyChunks.clear();

        send(context, state, new SetChunkCacheCenterPacket(center.x(), center.z()));
        var expired = streamedChunks.stream()
                .filter(position -> !desiredChunks.contains(position))
                .toList();
        for (var position : expired) {
            streamedChunks.remove(position);
            send(context, state, new ForgetLevelChunkPacket(position));
        }
        context.flush();

        var requested = desiredChunks.stream()
                .filter(position -> !streamedChunks.contains(position))
                .sorted(Comparator.comparingLong(position -> distanceSquared(position, center)))
                .toList();
        if (spawnPositionPending && requested.size() > INITIAL_CHUNKS_PER_BATCH) {
            requestChunkGroup(context, revision,
                    requested.subList(0, INITIAL_CHUNKS_PER_BATCH),
                    requested.subList(INITIAL_CHUNKS_PER_BATCH, requested.size()));
            return;
        }

        requestChunkGroup(context, revision, requested, List.of());
    }

    private void requestChunkGroup(ChannelHandlerContext context, long revision, List<ChunkPosition> positions,
                                   List<ChunkPosition> nextGroup) {
        CompletableFuture.supplyAsync(() -> loadChunks(positions), CHUNK_REQUEST_EXECUTOR)
                .whenComplete((chunks, error) -> context.executor().execute(() -> {
                    if (!finishChunkRequest(context, revision, chunks, error) || nextGroup.isEmpty()) {
                        return;
                    }
                    requestChunkGroup(context, revision, nextGroup, List.of());
                }));
    }

    private List<IChunk> loadChunks(List<ChunkPosition> positions) {
        var requests = positions.stream()
                .map(position -> CompletableFuture.supplyAsync(
                        () -> world.chunkNow(position.x(), position.z()), CHUNK_REQUEST_EXECUTOR))
                .toList();
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).join();
        return requests.stream().map(CompletableFuture::join).toList();
    }

    private boolean finishChunkRequest(ChannelHandlerContext context, long revision, List<IChunk> chunks,
                                       Throwable error) {
        if (!context.channel().isActive() || state != DefaultProtocolState.PLAY || revision != chunkStreamRevision) {
            return false;
        }
        if (error != null) {
            LOGGER.error("Could not prepare chunks for {}", context.channel().remoteAddress(), error);
            close(context);
            return false;
        }

        chunks.stream()
                .filter(chunk -> desiredChunks.contains(chunk.position()))
                .filter(chunk -> !streamedChunks.contains(chunk.position()))
                .forEach(readyChunks::addLast);
        sendNextChunkBatch(context);
        return true;
    }

    private void acknowledgeChunkBatch(ChannelHandlerContext context, ChunkBatchReceivedPacket acknowledgement) {
        batchInFlight = false;
        var requested = acknowledgement.desiredChunksPerTick();
        if (!Float.isFinite(requested)) {
            requested = 1.0F;
        }
        chunksPerBatch = Math.max(1, Math.min(MAX_CHUNKS_PER_BATCH, (int) requested));
        sendNextChunkBatch(context);
    }

    private void sendNextChunkBatch(ChannelHandlerContext context) {
        if (batchInFlight || readyChunks.isEmpty()) {
            return;
        }

        var batch = new ArrayList<IChunk>(chunksPerBatch);
        while (batch.size() < chunksPerBatch && !readyChunks.isEmpty()) {
            var chunk = readyChunks.removeFirst();
            if (!desiredChunks.contains(chunk.position()) || streamedChunks.contains(chunk.position())) {
                continue;
            }
            batch.add(chunk);
        }
        if (batch.isEmpty()) {
            return;
        }

        batchInFlight = true;
        send(context, state, new ChunkBatchStartPacket());
        for (var chunk : batch) {
            streamedChunks.add(chunk.position());
            send(context, state, new LevelChunkWithLightPacket(chunk));
        }
        send(context, state, new ChunkBatchFinishedPacket(batch.size()));
        if (spawnPositionPending) {
            var center = batch.stream()
                    .filter(chunk -> chunk.position().equals(chunkCenter))
                    .findFirst()
                    .orElseThrow();
            var spawnY = center.highestBlockY(0, 0) + 1.0;
            send(context, state, new PlayerPositionPacket(0, 0.5, spawnY, 0.5, 0.0F, 0.0F));
            spawnPositionPending = false;
        }
        context.flush();
    }

    private static Set<ChunkPosition> trackingChunks(ChunkPosition center) {
        var chunks = new HashSet<ChunkPosition>();
        for (var x = center.x() - TRACKING_RADIUS; x <= center.x() + TRACKING_RADIUS; x++) {
            for (var z = center.z() - TRACKING_RADIUS; z <= center.z() + TRACKING_RADIUS; z++) {
                chunks.add(new ChunkPosition(x, z));
            }
        }
        return Set.copyOf(chunks);
    }

    private static long distanceSquared(ChunkPosition position, ChunkPosition center) {
        var deltaX = (long) position.x() - center.x();
        var deltaZ = (long) position.z() - center.z();
        return deltaX * deltaX + deltaZ * deltaZ;
    }

    private static boolean validPosition(double coordinate) {
        return Double.isFinite(coordinate) && Math.abs(coordinate) <= MAXIMUM_POSITION;
    }

    private void sendKeepAlive(ChannelHandlerContext context) {
        if (!context.channel().isActive() || state != DefaultProtocolState.PLAY) {
            return;
        }

        sendAndFlush(context, state, new KeepAlivePacket(System.nanoTime()));
    }

    private void sendStatus(ChannelHandlerContext context) {
        if (step != ConnectionStep.STATUS) {
            close(context);
            return;
        }

        sendAndFlush(context, DefaultProtocolState.STATUS, new StatusResponsePacket(statusJson()));
    }

    private void sendPong(ChannelHandlerContext context, long payload) {
        if (step != ConnectionStep.STATUS) {
            close(context);
            return;
        }

        var packet = encode(context, DefaultProtocolState.STATUS, new PongResponsePacket(payload));
        context.writeAndFlush(packet).addListener(ignored -> close(context));
    }

    private String statusJson() {
        var version = new JsonObject();
        version.addProperty("name", ServerStatus.VERSION_NAME);
        version.addProperty("protocol", ServerStatus.PROTOCOL_VERSION);

        var players = new JsonObject();
        players.addProperty("max", status.maxPlayers());
        players.addProperty("online", 0);

        var description = new JsonObject();
        description.addProperty("text", status.description());

        var response = new JsonObject();
        response.add("version", version);
        response.add("players", players);
        response.add("description", description);
        return response.toString();
    }

    private void send(ChannelHandlerContext context, DefaultProtocolState protocolState, IPacket packet) {
        context.write(encode(context, protocolState, packet));
    }

    private void sendAndFlush(ChannelHandlerContext context, DefaultProtocolState protocolState, IPacket packet) {
        context.writeAndFlush(encode(context, protocolState, packet));
    }

    private ByteBuf encode(ChannelHandlerContext context, DefaultProtocolState protocolState, IPacket packet) {
        var output = context.alloc().buffer();

        try {
            registry.encode(protocolState, PacketFlow.CLIENTBOUND, output, packet);
            return output;
        } catch (RuntimeException error) {
            output.release();
            throw error;
        }
    }

    private static UUID offlineProfileId(String username) {
        var value = "OfflinePlayer:" + username;
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void close(ChannelHandlerContext context) {
        context.close();
    }

    private enum ConnectionStep {
        HANDSHAKE,
        STATUS,
        LOGIN_START,
        LOGIN_ACKNOWLEDGEMENT,
        KNOWN_PACKS,
        CONFIGURATION_ACKNOWLEDGEMENT,
        PLAY
    }
}
