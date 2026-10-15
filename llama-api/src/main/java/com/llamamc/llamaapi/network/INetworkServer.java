package com.llamamc.llamaapi.network;

import java.net.InetSocketAddress;

public interface INetworkServer {

    void start(InetSocketAddress address);

    void close();

    boolean isRunning();

    InetSocketAddress address();

}
