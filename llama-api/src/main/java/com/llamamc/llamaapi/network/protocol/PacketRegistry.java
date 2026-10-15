package com.llamamc.llamaapi.network.protocol;

import com.llamamc.llamaapi.network.codec.StreamCodec;
import io.netty.buffer.ByteBuf;

import java.util.HashMap;
import java.util.Map;

public final class PacketRegistry implements IProtocolRegistry {
    private final Map<PacketId, PacketDefinition<?>> packetsById = new HashMap<>();
    private final Map<PacketClass, PacketDefinition<?>> packetsByClass = new HashMap<>();

    private volatile Map<PacketId, PacketDefinition<?>> frozenPacketsById;
    private volatile Map<PacketClass, PacketDefinition<?>> frozenPacketsByClass;

    @Override
    public synchronized <P extends IPacket> PacketDefinition<P> register(IProtocolState state, PacketFlow flow,
                                                                           int id, Class<P> packetType,
                                                                           StreamCodec<ByteBuf, P> codec) {
        if (isFrozen()) {
            throw new IllegalStateException("Packet registry is frozen");
        }

        var definition = new PacketDefinition<>(state, flow, id, packetType, codec);
        var packetId = new PacketId(state.id(), flow, id);
        var packetClass = new PacketClass(state.id(), flow, packetType);

        if (packetsById.containsKey(packetId)) {
            throw new IllegalArgumentException("Packet id is already registered: " + packetId);
        }
        if (packetsByClass.containsKey(packetClass)) {
            throw new IllegalArgumentException("Packet type is already registered: " + packetClass);
        }

        packetsById.put(packetId, definition);
        packetsByClass.put(packetClass, definition);
        return definition;
    }

    @Override
    public IPacket decode(IProtocolState state, PacketFlow flow, ByteBuf input) {
        var packetId = readVarInt(input);
        return packetById(state, flow, packetId).codec().decode(input);
    }

    @Override
    public void encode(IProtocolState state, PacketFlow flow, ByteBuf output, IPacket packet) {
        if (packet == null) {
            throw new IllegalArgumentException("packet cannot be null");
        }

        var definition = packetByClass(state, flow, packet.getClass());
        writeVarInt(output, definition.id());
        encodePacket(output, definition, packet);
    }

    @Override
    public synchronized void freeze() {
        if (isFrozen()) {
            return;
        }

        frozenPacketsById = Map.copyOf(packetsById);
        frozenPacketsByClass = Map.copyOf(packetsByClass);
    }

    @Override
    public boolean isFrozen() {
        return frozenPacketsById != null;
    }

    private PacketDefinition<?> packetById(IProtocolState state, PacketFlow flow, int id) {
        var definition = packetsById().get(new PacketId(state.id(), flow, id));

        if (definition == null) {
            throw new UnknownPacketException(state, flow, id);
        }

        return definition;
    }

    private PacketDefinition<?> packetByClass(IProtocolState state, PacketFlow flow, Class<?> packetType) {
        var definition = packetsByClass().get(new PacketClass(state.id(), flow, packetType));

        if (definition == null) {
            throw new UnknownPacketException(state, flow, packetType.getName());
        }

        return definition;
    }

    private Map<PacketId, PacketDefinition<?>> packetsById() {
        if (frozenPacketsById != null) {
            return frozenPacketsById;
        }

        return Map.copyOf(packetsById);
    }

    private Map<PacketClass, PacketDefinition<?>> packetsByClass() {
        if (frozenPacketsByClass != null) {
            return frozenPacketsByClass;
        }

        return Map.copyOf(packetsByClass);
    }

    @SuppressWarnings("unchecked")
    private static <P extends IPacket> void encodePacket(ByteBuf output, PacketDefinition<P> definition,
                                                           IPacket packet) {
        definition.codec().encode(output, (P) packet);
    }

    private static int readVarInt(ByteBuf input) {
        var result = 0;

        for (var position = 0; position < 5; position++) {
            if (!input.isReadable()) {
                throw new PacketDecodingException("Incomplete VarInt packet id");
            }

            var value = input.readByte() & 0xFF;
            result |= (value & 0x7F) << position * 7;

            if ((value & 0x80) == 0) {
                return result;
            }
        }

        throw new PacketDecodingException("VarInt packet id is too large");
    }

    private static void writeVarInt(ByteBuf output, int value) {
        while ((value & ~0x7F) != 0) {
            output.writeByte(value & 0x7F | 0x80);
            value >>>= 7;
        }

        output.writeByte(value);
    }

    private record PacketId(String state, PacketFlow flow, int id) {
    }

    private record PacketClass(String state, PacketFlow flow, Class<?> type) {
    }
}
