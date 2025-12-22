package com.llamamc.llamaapi.network.protocol;

import com.llamamc.llamaapi.network.codec.StreamCodec;

public interface IProtocolExtensions {
    IExtensionNamespace namespace(String namespace);

    interface IExtensionNamespace {
        <B, P extends IPacket<?>> IExtensionNamespace addPacket(
                IProtocolState state,
                PacketFlow flow,
                String id,
                StreamCodec<B, P> codec
        );
        boolean isFrozen();
    }

}
