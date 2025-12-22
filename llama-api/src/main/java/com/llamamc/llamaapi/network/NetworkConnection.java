package com.llamamc.llamaapi.network;

import com.llamamc.llamaapi.component.Component;

import java.net.SocketAddress;
import java.util.UUID;

public interface NetworkConnection {

    UUID id();

    SocketAddress remoteAddress();

    void disconnect(Component reason);

    boolean isConnected();

}
