package com.llamamc.llamaapi;

import com.llamamc.llamaapi.event.IEventBus;

public interface ILlama {

    IEventBus eventBus();
    void shutdown();

}
