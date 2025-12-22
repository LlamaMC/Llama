package com.llamamc.llamaapi.network;

public interface IPacketView {

    PacketDirection direction();

    int corePacketId();

    String protocol();

    String typeName();

    int payloadBytes();

}
