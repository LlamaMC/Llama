package com.llamamc.llamaapi.network.protocol;

import com.llamamc.llamaapi.network.codec.StreamCodec;

public interface IProtocolInfo {
    IProtocolState state();
    PacketFlow flow();
    StreamCodec<?, IPacket<?>> codec();
}
