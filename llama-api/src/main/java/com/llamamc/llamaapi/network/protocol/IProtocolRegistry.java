package com.llamamc.llamaapi.network.protocol;

import java.util.Collection;

public interface IProtocolRegistry {
    Collection<PacketType> corePackets(IProtocolState state, PacketFlow flow);
    IPacketListenerFactory listenerFactory(IProtocolState state, PacketFlow flow);
}
