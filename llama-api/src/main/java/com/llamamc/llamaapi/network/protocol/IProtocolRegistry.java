package com.llamamc.llamaapi.network.protocol;

import com.llamamc.llamaapi.network.codec.StreamCodec;
import io.netty.buffer.ByteBuf;

public interface IProtocolRegistry {
    <P extends IPacket> PacketDefinition<P> register(IProtocolState state, PacketFlow flow, int id, Class<P> packetType, StreamCodec<ByteBuf, P> codec);

    IPacket decode(IProtocolState state, PacketFlow flow, ByteBuf input);

    void encode(IProtocolState state, PacketFlow flow, ByteBuf output, IPacket packet);

    void freeze();

    boolean isFrozen();
}
