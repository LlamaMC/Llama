package com.llamamc.llamaapi.network.protocol;

import com.llamamc.llamaapi.network.codec.StreamCodec;

public interface IProtocolCodecBuilder {

    <B, P extends IPacket<?>> IProtocolCodecBuilder add(PacketType type, StreamCodec<B, P> codec);
    <B> StreamCodec<B, IPacket<?>> build();

}
