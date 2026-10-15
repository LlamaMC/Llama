package com.llamamc.llamaapi.network.protocol;

import com.llamamc.llamaapi.network.codec.StreamCodec;
import io.netty.buffer.ByteBuf;

public record PacketDefinition<P extends IPacket>(IProtocolState state, PacketFlow flow, int id,
                                                   Class<P> packetType, StreamCodec<ByteBuf, P> codec) {

    public PacketDefinition {
        if (state == null) {
            throw new IllegalArgumentException("state cannot be null");
        }
        if (flow == null) {
            throw new IllegalArgumentException("flow cannot be null");
        }
        if (id < 0) {
            throw new IllegalArgumentException("id cannot be negative");
        }
        if (packetType == null) {
            throw new IllegalArgumentException("packetType cannot be null");
        }
        if (codec == null) {
            throw new IllegalArgumentException("codec cannot be null");
        }
    }
}
