package com.llamamc.llamaapi;

import com.llamamc.llamaapi.event.IEventBus;
import com.llamamc.llamaapi.network.INetworkServer;
import com.llamamc.llamaapi.world.IWorldManager;
import com.llamamc.llamaapi.registry.IRegistries;

public interface ILlama {

    INetworkServer server();
    IEventBus eventBus();
    IWorldManager worlds();
    IRegistries registries();
    void shutdown();

}
