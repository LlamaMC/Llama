package com.llamamc.llamaapi;

import com.llamamc.llamaapi.command.ICommandRegistry;
import com.llamamc.llamaapi.event.IEventBus;
import com.llamamc.llamaapi.network.INetworkServer;
import com.llamamc.llamaapi.scheduler.IScheduler;
import com.llamamc.llamaapi.world.IWorldManager;
import com.llamamc.llamaapi.registry.IRegistries;

public interface ILlama {

    INetworkServer server();
    IEventBus events();
    IScheduler scheduler();
    IWorldManager worlds();
    IRegistries registries();
    ICommandRegistry commands();
    void shutdown();

}
