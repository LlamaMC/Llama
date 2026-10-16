package com.llamamc.llama.network.minecraft.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.llamamc.llama.registry.MinecraftRegistries;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.network.codec.StreamCodec;
import com.llamamc.llamaapi.network.minecraft.packet.common.DisconnectPacket;
import com.llamamc.llamaapi.network.minecraft.packet.common.KeepAlivePacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.ClientInformationPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.ConfigurationAcknowledgedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.CustomPayloadPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.FinishConfigurationPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.KnownPack;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.KnownPacksSelectedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.RegistryDataPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.SelectKnownPacksPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.UpdateEnabledFeaturesPacket;
import com.llamamc.llamaapi.network.minecraft.packet.configuration.UpdateTagsPacket;
import com.llamamc.llamaapi.network.minecraft.packet.handshake.HandshakePacket;
import com.llamamc.llamaapi.network.minecraft.packet.login.LoginAcknowledgedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.login.LoginDisconnectPacket;
import com.llamamc.llamaapi.network.minecraft.packet.login.LoginFinishedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.login.LoginStartPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.AcceptTeleportationPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ChatCommandPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ChatCommandSignedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ChatPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ChunkBatchReceivedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ChunkBatchFinishedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ChunkBatchStartPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.CommandsPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.ForgetLevelChunkPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.GameEvent;
import com.llamamc.llamaapi.network.minecraft.packet.play.GameEventPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.LastSeenUpdate;
import com.llamamc.llamaapi.network.minecraft.packet.play.LevelChunkWithLightPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.MessageSignature;
import com.llamamc.llamaapi.network.minecraft.packet.play.MovePlayerPositionPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.MovePlayerPositionRotationPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.MovePlayerRotationPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.MovePlayerStatusPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.PlayLoginPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.PlayerLoadedPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.PlayerPositionPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.SetChunkCacheCenterPacket;
import com.llamamc.llamaapi.network.minecraft.packet.play.SystemChatPacket;
import com.llamamc.llamaapi.network.minecraft.packet.status.PingRequestPacket;
import com.llamamc.llamaapi.network.minecraft.packet.status.PongResponsePacket;
import com.llamamc.llamaapi.network.minecraft.packet.status.StatusRequestPacket;
import com.llamamc.llamaapi.network.minecraft.packet.status.StatusResponsePacket;
import com.llamamc.llamaapi.network.protocol.DefaultProtocolState;
import com.llamamc.llamaapi.network.protocol.PacketFlow;
import com.llamamc.llamaapi.network.protocol.PacketRegistry;
import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.ChunkPosition;
import com.llamamc.llamaapi.world.IChunk;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import io.netty.util.CharsetUtil;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Minecraft26_3Protocol {
    public static final int VERSION = 777;

    private static final int MAX_IDENTIFIER_CHARACTERS = 32_767;
    private static final int MAX_KNOWN_PACKS = 64;
    private static final int MAX_PROFILE_PROPERTIES = 16;
    private static final int MAX_USERNAME_CHARACTERS = 16;
    private static final int MAX_LOGIN_DISCONNECT_CHARACTERS = 262_144;
    private static final int MAX_CHAT_MESSAGE_CHARACTERS = 256;
    private static final int MAX_COMMAND_CHARACTERS = 32_767;
    private static final int MAX_ARGUMENT_SIGNATURES = 8;
    private static final int MAX_ARGUMENT_NAME_CHARACTERS = 16;
    private static final int LAST_SEEN_WINDOW_SIZE = 20;
    // Command node flags. The low bits hold the node type. Only the root and the executable literal are ever sent, so
    // the redirect, suggestion and restricted flags are neither written nor accepted.
    private static final int COMMAND_ROOT_INDEX = 0;
    private static final int COMMAND_ROOT_FLAGS = 0;
    private static final int COMMAND_TYPE_LITERAL = 1;
    private static final int COMMAND_FLAG_EXECUTABLE = 4;
    private static final int COMMAND_LITERAL_FLAGS = COMMAND_TYPE_LITERAL | COMMAND_FLAG_EXECUTABLE;
    private static final int NBT_TAG_STRING = 8;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Minecraft26_3Protocol() {
    }

    public static PacketRegistry createRegistry() {
        return createRegistry(new MinecraftRegistries(MinecraftProtocolVersion.V26_3));
    }

    public static PacketRegistry createRegistry(MinecraftRegistries gameRegistries) {
        var registry = new PacketRegistry();
        registerHandshake(registry);
        registerStatus(registry);
        registerLogin(registry);
        registerConfiguration(registry);
        registerPlay(registry, gameRegistries);
        registry.freeze();
        return registry;
    }

    private static void registerHandshake(PacketRegistry registry) {
        registry.register(DefaultProtocolState.HANDSHAKE, PacketFlow.SERVERBOUND, 0, HandshakePacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeHandshake, Minecraft26_3Protocol::readHandshake));
    }

    private static void registerStatus(PacketRegistry registry) {
        registry.register(DefaultProtocolState.STATUS, PacketFlow.SERVERBOUND, 0, StatusRequestPacket.class,
                StreamCodec.unit(new StatusRequestPacket()));
        registry.register(DefaultProtocolState.STATUS, PacketFlow.SERVERBOUND, 1, PingRequestPacket.class,
                StreamCodec.of((output, packet) -> output.writeLong(packet.payload()),
                        input -> new PingRequestPacket(input.readLong())));
        registry.register(DefaultProtocolState.STATUS, PacketFlow.CLIENTBOUND, 0, StatusResponsePacket.class,
                StreamCodec.of((output, packet) -> writeString(output, packet.json()),
                        input -> new StatusResponsePacket(readString(input, MAX_IDENTIFIER_CHARACTERS))));
        registry.register(DefaultProtocolState.STATUS, PacketFlow.CLIENTBOUND, 1, PongResponsePacket.class,
                StreamCodec.of((output, packet) -> output.writeLong(packet.payload()),
                        input -> new PongResponsePacket(input.readLong())));
    }

    private static void registerLogin(PacketRegistry registry) {
        registry.register(DefaultProtocolState.LOGIN, PacketFlow.SERVERBOUND, 0, LoginStartPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeLoginStart, Minecraft26_3Protocol::readLoginStart));
        registry.register(DefaultProtocolState.LOGIN, PacketFlow.SERVERBOUND, 3, LoginAcknowledgedPacket.class,
                StreamCodec.unit(new LoginAcknowledgedPacket()));
        registry.register(DefaultProtocolState.LOGIN, PacketFlow.CLIENTBOUND, 0, LoginDisconnectPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeLoginDisconnect,
                        Minecraft26_3Protocol::readLoginDisconnect));
        registry.register(DefaultProtocolState.LOGIN, PacketFlow.CLIENTBOUND, 2, LoginFinishedPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeLoginFinished,
                        Minecraft26_3Protocol::readLoginFinished));
    }

    private static void registerConfiguration(PacketRegistry registry) {
        registry.register(DefaultProtocolState.CONFIGURATION, PacketFlow.SERVERBOUND, 0,
                ClientInformationPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeClientInformation,
                        Minecraft26_3Protocol::readClientInformation));
        registry.register(DefaultProtocolState.CONFIGURATION, PacketFlow.SERVERBOUND, 2,
                CustomPayloadPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeCustomPayload,
                        Minecraft26_3Protocol::readCustomPayload));
        registry.register(DefaultProtocolState.CONFIGURATION, PacketFlow.SERVERBOUND, 3,
                ConfigurationAcknowledgedPacket.class, StreamCodec.unit(new ConfigurationAcknowledgedPacket()));
        registry.register(DefaultProtocolState.CONFIGURATION, PacketFlow.SERVERBOUND, 7,
                KnownPacksSelectedPacket.class,
                StreamCodec.of((output, packet) -> writeKnownPacks(output, packet.packs()),
                        input -> new KnownPacksSelectedPacket(readKnownPacks(input))));

        registry.register(DefaultProtocolState.CONFIGURATION, PacketFlow.CLIENTBOUND, 2, DisconnectPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeDisconnect, Minecraft26_3Protocol::readDisconnect));
        registry.register(DefaultProtocolState.CONFIGURATION, PacketFlow.CLIENTBOUND, 3,
                FinishConfigurationPacket.class, StreamCodec.unit(new FinishConfigurationPacket()));
        registry.register(DefaultProtocolState.CONFIGURATION, PacketFlow.CLIENTBOUND, 7,
                RegistryDataPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeRegistryData,
                        Minecraft26_3Protocol::readRegistryData));
        registry.register(DefaultProtocolState.CONFIGURATION, PacketFlow.CLIENTBOUND, 13,
                UpdateEnabledFeaturesPacket.class,
                StreamCodec.of((output, packet) -> writeIdentifiers(output, packet.features()),
                        input -> new UpdateEnabledFeaturesPacket(readIdentifiers(input))));
        registry.register(DefaultProtocolState.CONFIGURATION, PacketFlow.CLIENTBOUND, 14,
                UpdateTagsPacket.class, StreamCodec.of(Minecraft26_3Protocol::writeTags,
                        Minecraft26_3Protocol::readTags));
        registry.register(DefaultProtocolState.CONFIGURATION, PacketFlow.CLIENTBOUND, 15,
                SelectKnownPacksPacket.class,
                StreamCodec.of((output, packet) -> writeKnownPacks(output, packet.packs()),
                        input -> new SelectKnownPacksPacket(readKnownPacks(input))));
    }

    private static void registerPlay(PacketRegistry registry, MinecraftRegistries gameRegistries) {
        registry.register(DefaultProtocolState.PLAY, PacketFlow.SERVERBOUND, 0x00,
                AcceptTeleportationPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeAcceptTeleportation,
                        Minecraft26_3Protocol::readAcceptTeleportation));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.SERVERBOUND, 0x07, ChatCommandPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeChatCommand,
                        Minecraft26_3Protocol::readChatCommand));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.SERVERBOUND, 0x08, ChatCommandSignedPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeChatCommandSigned,
                        Minecraft26_3Protocol::readChatCommandSigned));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.SERVERBOUND, 0x09, ChatPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeChat, Minecraft26_3Protocol::readChat));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.SERVERBOUND, 0x0B,
                ChunkBatchReceivedPacket.class,
                StreamCodec.of((output, packet) -> output.writeFloat(packet.desiredChunksPerTick()),
                        input -> new ChunkBatchReceivedPacket(input.readFloat())));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.SERVERBOUND, 0x1E,
                MovePlayerPositionPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeMovePlayerPosition,
                        Minecraft26_3Protocol::readMovePlayerPosition));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.SERVERBOUND, 0x1F,
                MovePlayerPositionRotationPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeMovePlayerPositionRotation,
                        Minecraft26_3Protocol::readMovePlayerPositionRotation));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.SERVERBOUND, 0x20,
                MovePlayerRotationPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeMovePlayerRotation,
                        Minecraft26_3Protocol::readMovePlayerRotation));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.SERVERBOUND, 0x21,
                MovePlayerStatusPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeMovePlayerStatus,
                        Minecraft26_3Protocol::readMovePlayerStatus));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.SERVERBOUND, 0x2C,
                PlayerLoadedPacket.class, StreamCodec.unit(new PlayerLoadedPacket()));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, 0x0B, ChunkBatchFinishedPacket.class,
                StreamCodec.of((output, packet) -> writeVarInt(output, packet.batchSize()),
                        input -> new ChunkBatchFinishedPacket(readVarInt(input))));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, 0x0C, ChunkBatchStartPacket.class,
                StreamCodec.of((output, packet) -> { }, input -> new ChunkBatchStartPacket()));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, 0x10, CommandsPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeCommands, Minecraft26_3Protocol::readCommands));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.SERVERBOUND, 0x1C, KeepAlivePacket.class,
                StreamCodec.of((output, packet) -> output.writeLong(packet.id()),
                        input -> new KeepAlivePacket(input.readLong())));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, 0x2D, KeepAlivePacket.class,
                StreamCodec.of((output, packet) -> output.writeLong(packet.id()),
                        input -> new KeepAlivePacket(input.readLong())));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, 0x27, GameEventPacket.class,
                StreamCodec.of((output, packet) -> {
                    output.writeByte(packet.event().protocolId());
                    output.writeFloat(packet.value());
                }, input -> new GameEventPacket(GameEvent.byProtocolId(input.readUnsignedByte()), input.readFloat())));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, 0x26, ForgetLevelChunkPacket.class,
                StreamCodec.of((output, packet) -> writeChunkPosition(output, packet.position()),
                        input -> new ForgetLevelChunkPacket(readChunkPosition(input))));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, 0x2E, LevelChunkWithLightPacket.class,
                StreamCodec.of((output, packet) -> writeLevelChunk(output, packet, gameRegistries),
                        input -> {
                            throw new UnsupportedOperationException("Clientbound chunk decoding is not available");
                        }));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, 0x32, PlayLoginPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writePlayLogin, Minecraft26_3Protocol::readPlayLogin));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, 0x49, PlayerPositionPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writePlayerPosition,
                        Minecraft26_3Protocol::readPlayerPosition));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, 0x60, SetChunkCacheCenterPacket.class,
                StreamCodec.of((output, packet) -> {
                    writeVarInt(output, packet.chunkX());
                    writeVarInt(output, packet.chunkZ());
                }, input -> new SetChunkCacheCenterPacket(readVarInt(input), readVarInt(input))));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, 0x20, DisconnectPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeDisconnect, Minecraft26_3Protocol::readDisconnect));
        registry.register(DefaultProtocolState.PLAY, PacketFlow.CLIENTBOUND, 0x7C, SystemChatPacket.class,
                StreamCodec.of(Minecraft26_3Protocol::writeSystemChat, Minecraft26_3Protocol::readSystemChat));
    }

    private static HandshakePacket readHandshake(ByteBuf input) {
        var protocolVersion = readVarInt(input);
        var address = readString(input, 255);

        if (input.readableBytes() < Short.BYTES) {
            throw new DecoderException("Incomplete handshake port");
        }

        var port = input.readUnsignedShort();
        var intent = switch (readVarInt(input)) {
            case 1 -> HandshakePacket.Intent.STATUS;
            case 2 -> HandshakePacket.Intent.LOGIN;
            default -> throw new DecoderException("Unknown handshake intent");
        };

        requireFullyRead(input, "handshake");
        return new HandshakePacket(protocolVersion, address, port, intent);
    }

    private static void writeHandshake(ByteBuf output, HandshakePacket packet) {
        writeVarInt(output, packet.protocolVersion());
        writeString(output, packet.serverAddress(), 255);
        output.writeShort(packet.serverPort());
        writeVarInt(output, packet.intent() == HandshakePacket.Intent.STATUS ? 1 : 2);
    }

    private static LoginStartPacket readLoginStart(ByteBuf input) {
        var username = readString(input, MAX_USERNAME_CHARACTERS);
        var profileId = readUuid(input);
        requireFullyRead(input, "login start");
        return new LoginStartPacket(username, profileId);
    }

    private static void writeLoginStart(ByteBuf output, LoginStartPacket packet) {
        writeString(output, packet.username(), MAX_USERNAME_CHARACTERS);
        writeUuid(output, packet.profileId());
    }

    private static LoginFinishedPacket readLoginFinished(ByteBuf input) {
        var profileId = readUuid(input);
        var username = readString(input, MAX_USERNAME_CHARACTERS);
        var propertyCount = readCount(input, MAX_PROFILE_PROPERTIES, "profile properties");
        var properties = new ArrayList<LoginFinishedPacket.Property>(propertyCount);

        for (var index = 0; index < propertyCount; index++) {
            properties.add(readProfileProperty(input));
        }

        var sessionId = readUuid(input);
        requireFullyRead(input, "login finished");
        return new LoginFinishedPacket(profileId, username, properties, sessionId);
    }

    private static void writeLoginFinished(ByteBuf output, LoginFinishedPacket packet) {
        writeUuid(output, packet.profileId());
        writeString(output, packet.username(), MAX_USERNAME_CHARACTERS);
        writeCount(output, packet.properties().size(), MAX_PROFILE_PROPERTIES, "profile properties");

        for (var property : packet.properties()) {
            writeProfileProperty(output, property);
        }

        writeUuid(output, packet.sessionId());
    }

    private static LoginFinishedPacket.Property readProfileProperty(ByteBuf input) {
        var name = readString(input, 64);
        var value = readString(input, 32_767);
        var signature = input.readBoolean() ? readString(input, 1_024) : null;
        return new LoginFinishedPacket.Property(name, value, signature);
    }

    private static void writeProfileProperty(ByteBuf output, LoginFinishedPacket.Property property) {
        writeString(output, property.name(), 64);
        writeString(output, property.value(), 32_767);
        output.writeBoolean(property.signature() != null);

        if (property.signature() != null) {
            writeString(output, property.signature(), 1_024);
        }
    }

    private static List<KnownPack> readKnownPacks(ByteBuf input) {
        var count = readCount(input, MAX_KNOWN_PACKS, "known packs");
        var packs = new ArrayList<KnownPack>(count);

        for (var index = 0; index < count; index++) {
            packs.add(new KnownPack(readString(input), readString(input), readString(input)));
        }

        requireFullyRead(input, "known packs");
        return packs;
    }

    private static ClientInformationPacket readClientInformation(ByteBuf input) {
        var packet = new ClientInformationPacket(
                readString(input, 16),
                input.readByte(),
                readVarInt(input),
                input.readBoolean(),
                input.readUnsignedByte(),
                readVarInt(input),
                input.readBoolean(),
                input.readBoolean(),
                readVarInt(input)
        );
        requireFullyRead(input, "client information");
        return packet;
    }

    private static void writeClientInformation(ByteBuf output, ClientInformationPacket packet) {
        writeString(output, packet.language(), 16);
        output.writeByte(packet.viewDistance());
        writeVarInt(output, packet.chatVisibility());
        output.writeBoolean(packet.chatColors());
        output.writeByte(packet.modelCustomisation());
        writeVarInt(output, packet.mainHand());
        output.writeBoolean(packet.textFilteringEnabled());
        output.writeBoolean(packet.serverListingAllowed());
        writeVarInt(output, packet.particleStatus());
    }

    private static CustomPayloadPacket readCustomPayload(ByteBuf input) {
        var channel = readString(input);
        var data = new byte[input.readableBytes()];
        input.readBytes(data);
        return new CustomPayloadPacket(channel, data);
    }

    private static void writeCustomPayload(ByteBuf output, CustomPayloadPacket packet) {
        writeString(output, packet.channel());
        output.writeBytes(packet.data());
    }

    private static RegistryDataPacket readRegistryData(ByteBuf input) {
        var registry = readString(input);
        var count = readCount(input, Integer.MAX_VALUE, "registry entries");
        var entries = new ArrayList<RegistryDataPacket.Entry>(Math.min(count, 65_536));

        for (var index = 0; index < count; index++) {
            var identifier = readString(input);

            if (input.readBoolean()) {
                throw new DecoderException("Inline registry NBT is not supported yet");
            }

            entries.add(new RegistryDataPacket.Entry(identifier));
        }

        requireFullyRead(input, "registry data");
        return new RegistryDataPacket(registry, entries);
    }

    private static void writeRegistryData(ByteBuf output, RegistryDataPacket packet) {
        writeString(output, packet.registry());
        writeVarInt(output, packet.entries().size());

        for (var entry : packet.entries()) {
            writeString(output, entry.identifier());
            output.writeBoolean(false);
        }
    }

    private static void writeKnownPacks(ByteBuf output, List<KnownPack> packs) {
        writeCount(output, packs.size(), MAX_KNOWN_PACKS, "known packs");

        for (var pack : packs) {
            writeString(output, pack.namespace());
            writeString(output, pack.id());
            writeString(output, pack.version());
        }
    }

    private static Set<String> readIdentifiers(ByteBuf input) {
        var count = readCount(input, Integer.MAX_VALUE, "identifiers");
        var identifiers = new HashSet<String>(Math.min(count, 65_536));

        for (var index = 0; index < count; index++) {
            identifiers.add(readString(input));
        }

        requireFullyRead(input, "identifiers");
        return identifiers;
    }

    private static void writeIdentifiers(ByteBuf output, Set<String> identifiers) {
        writeVarInt(output, identifiers.size());

        for (var identifier : identifiers) {
            writeString(output, identifier);
        }
    }

    private static UpdateTagsPacket readTags(ByteBuf input) {
        var registryCount = readCount(input, Integer.MAX_VALUE, "tag registries");
        var registries = new LinkedHashMap<String, List<UpdateTagsPacket.Tag>>(Math.min(registryCount, 65_536));

        for (var registryIndex = 0; registryIndex < registryCount; registryIndex++) {
            var registry = readString(input);
            var tagCount = readCount(input, Integer.MAX_VALUE, "tags");
            var tags = new ArrayList<UpdateTagsPacket.Tag>(Math.min(tagCount, 65_536));

            for (var tagIndex = 0; tagIndex < tagCount; tagIndex++) {
                var identifier = readString(input);
                var entryCount = readCount(input, Integer.MAX_VALUE, "tag entries");
                var entries = new ArrayList<Integer>(Math.min(entryCount, 65_536));

                for (var entryIndex = 0; entryIndex < entryCount; entryIndex++) {
                    entries.add(readVarInt(input));
                }

                tags.add(new UpdateTagsPacket.Tag(identifier, entries));
            }

            registries.put(registry, tags);
        }

        requireFullyRead(input, "tags");
        return new UpdateTagsPacket(registries);
    }

    private static void writeTags(ByteBuf output, UpdateTagsPacket packet) {
        writeVarInt(output, packet.registries().size());

        packet.registries().forEach((registry, tags) -> {
            writeString(output, registry);
            writeVarInt(output, tags.size());

            for (var tag : tags) {
                writeString(output, tag.identifier());
                writeVarInt(output, tag.entries().size());

                for (var entry : tag.entries()) {
                    writeVarInt(output, entry);
                }
            }
        });
    }

    private static CommandsPacket readCommands(ByteBuf input) {
        var count = readCount(input, Integer.MAX_VALUE, "command nodes");
        var nodes = new ArrayList<WireCommandNode>(Math.min(count, 65_536));

        for (var index = 0; index < count; index++) {
            nodes.add(readCommandNode(input));
        }
        var rootIndex = readVarInt(input);
        requireFullyRead(input, "commands");

        return new CommandsPacket(commandNames(nodes, rootIndex));
    }

    private static WireCommandNode readCommandNode(ByteBuf input) {
        if (!input.isReadable()) {
            throw new DecoderException("Incomplete command node");
        }
        var flags = input.readUnsignedByte();
        var childCount = readCount(input, Integer.MAX_VALUE, "command children");
        var children = new ArrayList<Integer>(Math.min(childCount, 65_536));

        for (var index = 0; index < childCount; index++) {
            children.add(readVarInt(input));
        }
        if (flags == COMMAND_ROOT_FLAGS) {
            return new WireCommandNode(children, null);
        }
        if (flags != COMMAND_LITERAL_FLAGS || !children.isEmpty()) {
            throw new DecoderException("Unsupported command node flags: " + flags);
        }
        return new WireCommandNode(children, readString(input));
    }

    // Node 0 must be the root, and every other node must be a literal child of it. Anything else is rejected.
    private static List<String> commandNames(List<WireCommandNode> nodes, int rootIndex) {
        if (nodes.isEmpty() || rootIndex != COMMAND_ROOT_INDEX || nodes.getFirst().literal() != null) {
            throw new DecoderException("Unsupported command root at index " + rootIndex);
        }
        var children = nodes.getFirst().children();
        var reached = new HashSet<Integer>();
        var distinct = new HashSet<String>();
        var names = new ArrayList<String>(children.size());

        for (var child : children) {
            if (child < 1 || child >= nodes.size() || !reached.add(child)) {
                throw new DecoderException("Invalid command child index: " + child);
            }
            var name = nodes.get(child).literal();
            if (name == null || name.isBlank() || !distinct.add(name)) {
                throw new DecoderException("Invalid command name at index " + child);
            }
            names.add(name);
        }
        if (names.size() != nodes.size() - 1) {
            throw new DecoderException("Command node is not a child of the root");
        }
        return names;
    }

    private static void writeCommands(ByteBuf output, CommandsPacket packet) {
        var names = packet.commandNames();

        writeVarInt(output, names.size() + 1);
        writeCommandRoot(output, names.size());
        for (var name : names) {
            writeCommandLiteral(output, name);
        }
        writeVarInt(output, COMMAND_ROOT_INDEX);
    }

    // The root is node 0. Its children are the literals that follow it, numbered 1 to the name count.
    private static void writeCommandRoot(ByteBuf output, int literalCount) {
        output.writeByte(COMMAND_ROOT_FLAGS);
        writeVarInt(output, literalCount);

        for (var index = 1; index <= literalCount; index++) {
            writeVarInt(output, index);
        }
    }

    // A literal is executable, has no children of its own, and carries its name as a string.
    private static void writeCommandLiteral(ByteBuf output, String name) {
        output.writeByte(COMMAND_LITERAL_FLAGS);
        writeVarInt(output, 0);
        writeString(output, name);
    }

    private record WireCommandNode(List<Integer> children, String literal) {
    }

    private static AcceptTeleportationPacket readAcceptTeleportation(ByteBuf input) {
        var packet = new AcceptTeleportationPacket(
                readVarInt(input),
                input.readDouble(),
                input.readDouble(),
                input.readDouble(),
                input.readFloat(),
                input.readFloat()
        );
        requireFullyRead(input, "accept teleportation");
        return packet;
    }

    private static void writeAcceptTeleportation(ByteBuf output, AcceptTeleportationPacket packet) {
        writeVarInt(output, packet.teleportId());
        output.writeDouble(packet.x());
        output.writeDouble(packet.y());
        output.writeDouble(packet.z());
        output.writeFloat(packet.yaw());
        output.writeFloat(packet.pitch());
    }

    private static MovePlayerPositionPacket readMovePlayerPosition(ByteBuf input) {
        var x = input.readDouble();
        var y = input.readDouble();
        var z = input.readDouble();
        var flags = input.readUnsignedByte();
        requireFullyRead(input, "move player position");
        return new MovePlayerPositionPacket(x, y, z, (flags & 1) != 0, (flags & 2) != 0);
    }

    private static void writeMovePlayerPosition(ByteBuf output, MovePlayerPositionPacket packet) {
        output.writeDouble(packet.x());
        output.writeDouble(packet.y());
        output.writeDouble(packet.z());
        output.writeByte(movementFlags(packet.onGround(), packet.horizontalCollision()));
    }

    private static MovePlayerPositionRotationPacket readMovePlayerPositionRotation(ByteBuf input) {
        var x = input.readDouble();
        var y = input.readDouble();
        var z = input.readDouble();
        var yaw = input.readFloat();
        var pitch = input.readFloat();
        var flags = input.readUnsignedByte();
        requireFullyRead(input, "move player position rotation");
        return new MovePlayerPositionRotationPacket(
                x, y, z, yaw, pitch, (flags & 1) != 0, (flags & 2) != 0);
    }

    private static void writeMovePlayerPositionRotation(ByteBuf output,
                                                        MovePlayerPositionRotationPacket packet) {
        output.writeDouble(packet.x());
        output.writeDouble(packet.y());
        output.writeDouble(packet.z());
        output.writeFloat(packet.yaw());
        output.writeFloat(packet.pitch());
        output.writeByte(movementFlags(packet.onGround(), packet.horizontalCollision()));
    }

    private static MovePlayerRotationPacket readMovePlayerRotation(ByteBuf input) {
        var yaw = input.readFloat();
        var pitch = input.readFloat();
        var flags = input.readUnsignedByte();
        requireFullyRead(input, "move player rotation");
        return new MovePlayerRotationPacket(yaw, pitch, (flags & 1) != 0, (flags & 2) != 0);
    }

    private static void writeMovePlayerRotation(ByteBuf output, MovePlayerRotationPacket packet) {
        output.writeFloat(packet.yaw());
        output.writeFloat(packet.pitch());
        output.writeByte(movementFlags(packet.onGround(), packet.horizontalCollision()));
    }

    private static MovePlayerStatusPacket readMovePlayerStatus(ByteBuf input) {
        var flags = input.readUnsignedByte();
        requireFullyRead(input, "move player status");
        return new MovePlayerStatusPacket((flags & 1) != 0, (flags & 2) != 0);
    }

    private static void writeMovePlayerStatus(ByteBuf output, MovePlayerStatusPacket packet) {
        output.writeByte(movementFlags(packet.onGround(), packet.horizontalCollision()));
    }

    private static int movementFlags(boolean onGround, boolean horizontalCollision) {
        var flags = 0;
        if (onGround) {
            flags |= 1;
        }
        if (horizontalCollision) {
            flags |= 2;
        }
        return flags;
    }

    private static ChunkPosition readChunkPosition(ByteBuf input) {
        var packed = input.readLong();
        requireFullyRead(input, "chunk position");
        return new ChunkPosition((int) packed, (int) (packed >>> Integer.SIZE));
    }

    private static void writeChunkPosition(ByteBuf output, ChunkPosition position) {
        output.writeLong(Integer.toUnsignedLong(position.x()) | Integer.toUnsignedLong(position.z()) << Integer.SIZE);
    }

    private static PlayLoginPacket readPlayLogin(ByteBuf input) {
        var playerId = input.readInt();
        input.readBoolean();

        var levelCount = readCount(input, Integer.MAX_VALUE, "levels");
        for (var index = 0; index < levelCount; index++) {
            readString(input);
        }

        var maxPlayers = readVarInt(input);
        var viewDistance = readVarInt(input);
        var simulationDistance = readVarInt(input);
        input.skipBytes(3);
        readCommonSpawnInfo(input);
        input.skipBytes(2);
        requireFullyRead(input, "play login");
        return new PlayLoginPacket(playerId, maxPlayers, viewDistance, simulationDistance);
    }

    private static void writePlayLogin(ByteBuf output, PlayLoginPacket packet) {
        output.writeInt(packet.playerId());
        output.writeBoolean(false);
        writeVarInt(output, 1);
        writeString(output, "minecraft:overworld");
        writeVarInt(output, packet.maxPlayers());
        writeVarInt(output, packet.viewDistance());
        writeVarInt(output, packet.simulationDistance());
        output.writeBoolean(false);
        output.writeBoolean(true);
        output.writeBoolean(false);
        writeCommonSpawnInfo(output);
        output.writeBoolean(false);
        output.writeBoolean(false);
    }

    private static void readCommonSpawnInfo(ByteBuf input) {
        readVarInt(input);
        readString(input);
        input.readLong();
        readVarInt(input);
        readVarInt(input);
        input.readBoolean();
        input.readBoolean();

        if (input.readBoolean()) {
            readString(input);
            input.readLong();
        }

        readVarInt(input);
        readVarInt(input);
    }

    private static void writeCommonSpawnInfo(ByteBuf output) {
        writeVarInt(output, 0);
        writeString(output, "minecraft:overworld");
        output.writeLong(0L);
        writeVarInt(output, 0);
        writeVarInt(output, 0);
        output.writeBoolean(false);
        output.writeBoolean(false);
        output.writeBoolean(false);
        writeVarInt(output, 0);
        writeVarInt(output, 63);
    }

    private static PlayerPositionPacket readPlayerPosition(ByteBuf input) {
        var teleportId = readVarInt(input);
        var x = input.readDouble();
        var y = input.readDouble();
        var z = input.readDouble();
        input.skipBytes(Double.BYTES * 3);
        var yaw = input.readFloat();
        var pitch = input.readFloat();
        input.readInt();
        requireFullyRead(input, "player position");
        return new PlayerPositionPacket(teleportId, x, y, z, yaw, pitch);
    }

    private static void writePlayerPosition(ByteBuf output, PlayerPositionPacket packet) {
        writeVarInt(output, packet.teleportId());
        output.writeDouble(packet.x());
        output.writeDouble(packet.y());
        output.writeDouble(packet.z());
        output.writeDouble(0.0);
        output.writeDouble(0.0);
        output.writeDouble(0.0);
        output.writeFloat(packet.yaw());
        output.writeFloat(packet.pitch());
        output.writeInt(0);
    }

    private static void writeLevelChunk(ByteBuf output, LevelChunkWithLightPacket packet,
                                        MinecraftRegistries gameRegistries) {
        var chunk = packet.chunk();
        output.writeInt(chunk.position().x());
        output.writeInt(chunk.position().z());
        writeHeightmaps(output, chunk);

        var sections = output.alloc().buffer();
        try {
            for (var sectionY = 0; sectionY < chunk.height() / 16; sectionY++) {
                writeSection(sections, chunk, sectionY, gameRegistries);
            }
            writeVarInt(output, sections.readableBytes());
            output.writeBytes(sections);
        } finally {
            sections.release();
        }

        writeVarInt(output, 0);
        writeFullSkyLight(output, chunk.height() / 16 + 2);
    }

    private static void writeHeightmaps(ByteBuf output, IChunk chunk) {
        var heights = new int[256];
        for (var localZ = 0; localZ < 16; localZ++) {
            for (var localX = 0; localX < 16; localX++) {
                heights[localZ * 16 + localX] = chunk.highestBlockY(localX, localZ) + 1 - chunk.minY();
            }
        }
        var packed = pack(heights, 9);
        writeVarInt(output, 2);
        writeVarInt(output, 1);
        writeLongArray(output, packed);
        writeVarInt(output, 4);
        writeLongArray(output, packed);
    }

    private static void writeSection(ByteBuf output, IChunk chunk, int sectionY,
                                     MinecraftRegistries gameRegistries) {
        var palette = new LinkedHashMap<BlockState, Integer>();
        var values = new int[4096];
        var nonAir = 0;
        var fluids = 0;
        var index = 0;
        var blockY = chunk.minY() + sectionY * 16;

        for (var localY = 0; localY < 16; localY++) {
            for (var localZ = 0; localZ < 16; localZ++) {
                for (var localX = 0; localX < 16; localX++) {
                    var state = chunk.block(localX, blockY + localY, localZ);
                    values[index++] = palette.computeIfAbsent(state, ignored -> palette.size());
                    if (!state.identifier().equals("minecraft:air")) {
                        nonAir++;
                    }
                    if (isFluid(state)) {
                        fluids++;
                    }
                }
            }
        }

        output.writeShort(nonAir);
        output.writeShort(fluids);
        writeBlockStates(output, palette, values, gameRegistries);

        output.writeByte(0);
        writeVarInt(output, 41);
    }

    private static void writeBlockStates(ByteBuf output, Map<BlockState, Integer> palette, int[] values,
                                         MinecraftRegistries gameRegistries) {
        switch (PaletteMode.select(palette.size())) {
            case SINGLE -> {
                output.writeByte(0);
                writeVarInt(output, gameRegistries.blockStateId(palette.keySet().iterator().next()));
            }
            case INDIRECT -> {
                var bits = PaletteMode.indirectBits(palette.size());
                output.writeByte(bits);
                writeVarInt(output, palette.size());
                for (var state : palette.keySet()) {
                    writeVarInt(output, gameRegistries.blockStateId(state));
                }
                writePackedLongs(output, pack(values, bits));
            }
            case DIRECT -> {
                var bits = PaletteMode.directBits(gameRegistries.maxBlockStateId());
                var globalIds = new int[palette.size()];
                for (var entry : palette.entrySet()) {
                    globalIds[entry.getValue()] = gameRegistries.blockStateId(entry.getKey());
                }
                var globalValues = new int[values.length];
                for (var index = 0; index < values.length; index++) {
                    globalValues[index] = globalIds[values[index]];
                }
                output.writeByte(bits);
                writePackedLongs(output, pack(globalValues, bits));
            }
        }
    }

    private static void writePackedLongs(ByteBuf output, long[] values) {
        for (var value : values) {
            output.writeLong(value);
        }
    }

    private static long[] pack(int[] values, int bits) {
        var valuesPerLong = Long.SIZE / bits;
        var packed = new long[(values.length + valuesPerLong - 1) / valuesPerLong];
        var mask = (1L << bits) - 1;
        for (var index = 0; index < values.length; index++) {
            var longIndex = index / valuesPerLong;
            var bitIndex = index % valuesPerLong * bits;
            packed[longIndex] |= (values[index] & mask) << bitIndex;
        }
        return packed;
    }

    private static void writeFullSkyLight(ByteBuf output, int lightSections) {
        writeSetBits(output, lightSections);
        writeVarInt(output, 0);
        writeVarInt(output, 0);
        writeVarInt(output, 0);
        writeVarInt(output, lightSections);
        var fullLight = new byte[2048];
        Arrays.fill(fullLight, (byte) 0xFF);
        for (var section = 0; section < lightSections; section++) {
            writeVarInt(output, fullLight.length);
            output.writeBytes(fullLight);
        }
        writeVarInt(output, 0);
    }

    private static boolean isFluid(BlockState state) {
        return state.identifier().equals("minecraft:water") || state.identifier().equals("minecraft:lava");
    }

    private static void writeSetBits(ByteBuf output, int bits) {
        var bytes = (bits + Byte.SIZE - 1) / Byte.SIZE;
        writeVarInt(output, bytes);
        for (var index = 0; index < bytes; index++) {
            var remaining = bits - index * Byte.SIZE;
            output.writeByte(remaining >= Byte.SIZE ? 0xFF : (1 << remaining) - 1);
        }
    }

    private static void writeLongArray(ByteBuf output, long[] values) {
        writeVarInt(output, values.length);
        for (var value : values) {
            output.writeLong(value);
        }
    }

    private static LoginDisconnectPacket readLoginDisconnect(ByteBuf input) {
        var reason = readJsonTextComponent(input);
        requireFullyRead(input, "login disconnect");
        return new LoginDisconnectPacket(reason);
    }

    private static void writeLoginDisconnect(ByteBuf output, LoginDisconnectPacket packet) {
        writeJsonTextComponent(output, packet.reason());
    }

    private static DisconnectPacket readDisconnect(ByteBuf input) {
        var reason = readNbtTextComponent(input);
        requireFullyRead(input, "disconnect");
        return new DisconnectPacket(reason);
    }

    private static void writeDisconnect(ByteBuf output, DisconnectPacket packet) {
        writeNbtTextComponent(output, packet.reason());
    }

    private static SystemChatPacket readSystemChat(ByteBuf input) {
        var content = readNbtTextComponent(input);
        var overlay = input.readBoolean();
        requireFullyRead(input, "system chat");
        return new SystemChatPacket(content, overlay);
    }

    private static void writeSystemChat(ByteBuf output, SystemChatPacket packet) {
        writeNbtTextComponent(output, packet.content());
        output.writeBoolean(packet.overlay());
    }

    // Play and configuration text components use network NBT. A plain text component is one NBT string tag.
    private static Component readNbtTextComponent(ByteBuf input) {
        var tagType = input.readByte();

        if (tagType != NBT_TAG_STRING) {
            throw new DecoderException("Unsupported text component tag type: " + tagType);
        }

        try {
            return new Component(new ByteBufInputStream(input).readUTF());
        } catch (IOException exception) {
            throw new DecoderException("Invalid text component string", exception);
        }
    }

    private static void writeNbtTextComponent(ByteBuf output, Component component) {
        if (component == null) {
            throw new IllegalArgumentException("text component cannot be null");
        }

        output.writeByte(NBT_TAG_STRING);

        try {
            new ByteBufOutputStream(output).writeUTF(component.text());
        } catch (IOException exception) {
            throw new EncoderException("Text component cannot be encoded as an NBT string", exception);
        }
    }

    // The login state carries text components as a JSON string inside a Minecraft string.
    private static Component readJsonTextComponent(ByteBuf input) {
        var json = readString(input, MAX_LOGIN_DISCONNECT_CHARACTERS);
        JsonElement element;

        try {
            element = JsonParser.parseString(json);
        } catch (JsonParseException exception) {
            throw new DecoderException("Invalid text component JSON", exception);
        }

        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new DecoderException("Unsupported text component JSON, only plain text is supported");
        }

        return new Component(element.getAsString());
    }

    private static void writeJsonTextComponent(ByteBuf output, Component component) {
        if (component == null) {
            throw new IllegalArgumentException("text component cannot be null");
        }

        writeString(output, GSON.toJson(new JsonPrimitive(component.text())), MAX_LOGIN_DISCONNECT_CHARACTERS);
    }

    private static ChatPacket readChat(ByteBuf input) {
        var message = readString(input, MAX_CHAT_MESSAGE_CHARACTERS);
        var timestamp = Instant.ofEpochMilli(input.readLong());
        var salt = input.readLong();
        var signature = readOptionalMessageSignature(input);
        var lastSeenMessages = readLastSeenUpdate(input);
        requireFullyRead(input, "chat");
        return new ChatPacket(message, timestamp, salt, signature, lastSeenMessages);
    }

    private static void writeChat(ByteBuf output, ChatPacket packet) {
        writeString(output, packet.message(), MAX_CHAT_MESSAGE_CHARACTERS);
        output.writeLong(packet.timestamp().toEpochMilli());
        output.writeLong(packet.salt());
        writeOptionalMessageSignature(output, packet.signature());
        writeLastSeenUpdate(output, packet.lastSeenMessages());
    }

    private static ChatCommandPacket readChatCommand(ByteBuf input) {
        var command = readString(input, MAX_COMMAND_CHARACTERS);
        requireFullyRead(input, "chat command");
        return new ChatCommandPacket(command);
    }

    private static void writeChatCommand(ByteBuf output, ChatCommandPacket packet) {
        writeString(output, packet.command(), MAX_COMMAND_CHARACTERS);
    }

    private static ChatCommandSignedPacket readChatCommandSigned(ByteBuf input) {
        var command = readString(input, MAX_COMMAND_CHARACTERS);
        var timestamp = Instant.ofEpochMilli(input.readLong());
        var salt = input.readLong();
        var argumentSignatures = readArgumentSignatures(input);
        var lastSeenMessages = readLastSeenUpdate(input);
        requireFullyRead(input, "signed chat command");
        return new ChatCommandSignedPacket(command, timestamp, salt, argumentSignatures, lastSeenMessages);
    }

    private static void writeChatCommandSigned(ByteBuf output, ChatCommandSignedPacket packet) {
        writeString(output, packet.command(), MAX_COMMAND_CHARACTERS);
        output.writeLong(packet.timestamp().toEpochMilli());
        output.writeLong(packet.salt());
        writeArgumentSignatures(output, packet.argumentSignatures());
        writeLastSeenUpdate(output, packet.lastSeenMessages());
    }

    private static List<ChatCommandSignedPacket.ArgumentSignature> readArgumentSignatures(ByteBuf input) {
        var count = readCount(input, MAX_ARGUMENT_SIGNATURES, "argument signatures");
        var signatures = new ArrayList<ChatCommandSignedPacket.ArgumentSignature>(count);

        for (var index = 0; index < count; index++) {
            var name = readString(input, MAX_ARGUMENT_NAME_CHARACTERS);
            var signature = readMessageSignature(input);
            signatures.add(new ChatCommandSignedPacket.ArgumentSignature(name, signature));
        }

        return signatures;
    }

    private static void writeArgumentSignatures(ByteBuf output,
                                                List<ChatCommandSignedPacket.ArgumentSignature> signatures) {
        writeCount(output, signatures.size(), MAX_ARGUMENT_SIGNATURES, "argument signatures");

        for (var signature : signatures) {
            writeString(output, signature.name(), MAX_ARGUMENT_NAME_CHARACTERS);
            writeMessageSignature(output, signature.signature());
        }
    }

    private static MessageSignature readOptionalMessageSignature(ByteBuf input) {
        if (!input.readBoolean()) {
            return null;
        }

        return readMessageSignature(input);
    }

    private static void writeOptionalMessageSignature(ByteBuf output, MessageSignature signature) {
        output.writeBoolean(signature != null);

        if (signature == null) {
            return;
        }

        writeMessageSignature(output, signature);
    }

    private static MessageSignature readMessageSignature(ByteBuf input) {
        var bytes = new byte[MessageSignature.BYTES];
        input.readBytes(bytes);
        return new MessageSignature(bytes);
    }

    private static void writeMessageSignature(ByteBuf output, MessageSignature signature) {
        output.writeBytes(signature.bytes());
    }

    private static LastSeenUpdate readLastSeenUpdate(ByteBuf input) {
        var offset = readVarInt(input);
        var acknowledged = readFixedBitSet(input, LAST_SEEN_WINDOW_SIZE);
        var checksum = input.readByte();
        return new LastSeenUpdate(offset, acknowledged, checksum);
    }

    private static void writeLastSeenUpdate(ByteBuf output, LastSeenUpdate update) {
        writeVarInt(output, update.offset());
        writeFixedBitSet(output, update.acknowledged(), LAST_SEEN_WINDOW_SIZE);
        output.writeByte(update.checksum());
    }

    // Bit i lives in bit (i % 8) of byte (i / 8), as java.util.BitSet stores it.
    private static BitSet readFixedBitSet(ByteBuf input, int size) {
        var bytes = new byte[fixedBitSetBytes(size)];
        input.readBytes(bytes);
        return BitSet.valueOf(bytes);
    }

    private static void writeFixedBitSet(ByteBuf output, BitSet bits, int size) {
        if (bits.length() > size) {
            throw new EncoderException("Bit set has " + bits.length() + " bits, the limit is " + size);
        }

        output.writeBytes(Arrays.copyOf(bits.toByteArray(), fixedBitSetBytes(size)));
    }

    private static int fixedBitSetBytes(int size) {
        return (size + Byte.SIZE - 1) / Byte.SIZE;
    }

    private static UUID readUuid(ByteBuf input) {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeUuid(ByteBuf output, UUID uuid) {
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    private static String readString(ByteBuf input) {
        return readString(input, MAX_IDENTIFIER_CHARACTERS);
    }

    private static String readString(ByteBuf input, int maximumCharacters) {
        var length = readVarInt(input);
        var maximumBytes = ByteBufUtil.utf8MaxBytes(maximumCharacters);

        if (length < 0 || length > maximumBytes || input.readableBytes() < length) {
            throw new DecoderException("Invalid Minecraft string byte length: " + length);
        }

        var value = input.toString(input.readerIndex(), length, CharsetUtil.UTF_8);
        input.skipBytes(length);

        if (value.length() > maximumCharacters) {
            throw new DecoderException("Minecraft string exceeds " + maximumCharacters + " characters");
        }

        return value;
    }

    private static void writeString(ByteBuf output, String value) {
        writeString(output, value, MAX_IDENTIFIER_CHARACTERS);
    }

    private static void writeString(ByteBuf output, String value, int maximumCharacters) {
        if (value == null) {
            throw new IllegalArgumentException("value cannot be null");
        }
        if (value.length() > maximumCharacters) {
            throw new EncoderException("Minecraft string exceeds " + maximumCharacters + " characters");
        }

        var bytes = value.getBytes(CharsetUtil.UTF_8);
        var maximumBytes = ByteBufUtil.utf8MaxBytes(maximumCharacters);

        if (bytes.length > maximumBytes) {
            throw new EncoderException("Minecraft string exceeds " + maximumBytes + " UTF-8 bytes");
        }

        writeVarInt(output, bytes.length);
        output.writeBytes(bytes);
    }

    private static int readCount(ByteBuf input, int maximum, String subject) {
        var count = readVarInt(input);

        if (count < 0 || count > maximum) {
            throw new DecoderException("Invalid " + subject + " count: " + count);
        }

        return count;
    }

    private static void writeCount(ByteBuf output, int count, int maximum, String subject) {
        if (count < 0 || count > maximum) {
            throw new EncoderException("Invalid " + subject + " count: " + count);
        }

        writeVarInt(output, count);
    }

    private static int readVarInt(ByteBuf input) {
        var result = 0;

        for (var position = 0; position < 5; position++) {
            if (!input.isReadable()) {
                throw new DecoderException("Incomplete Minecraft VarInt");
            }

            var value = input.readByte() & 0xFF;
            result |= (value & 0x7F) << position * 7;

            if ((value & 0x80) == 0) {
                return result;
            }
        }

        throw new DecoderException("Minecraft VarInt is too large");
    }

    private static void writeVarInt(ByteBuf output, int value) {
        while ((value & ~0x7F) != 0) {
            output.writeByte(value & 0x7F | 0x80);
            value >>>= 7;
        }

        output.writeByte(value);
    }

    private static void requireFullyRead(ByteBuf input, String packetName) {
        if (input.isReadable()) {
            throw new DecoderException("Unexpected " + packetName + " data: " + input.readableBytes() + " bytes");
        }
    }
}
