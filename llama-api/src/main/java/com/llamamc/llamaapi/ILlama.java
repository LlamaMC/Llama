package com.llamamc.llamaapi;

import com.llamamc.llamaapi.event.IEventBus;
import com.llamamc.llamaapi.network.INetworkServer;

public interface ILlama {

    INetworkServer server();
    IEventBus eventBus();
    void shutdown();

}
