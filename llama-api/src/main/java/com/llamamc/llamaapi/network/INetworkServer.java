package com.llamamc.llamaapi.network;

import com.llamamc.llamaapi.concurrent.LlamaFuture;

import java.net.InetSocketAddress;
import java.util.Collection;

public interface INetworkServer {

    INetworkListener listener(String id);

    Collection<INetworkListener> listeners();

    LlamaFuture<Void> bind(InetSocketAddress address);

    LlamaFuture<Void> shutdown();

}
