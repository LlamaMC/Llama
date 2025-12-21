package com.llamamc.llamaapi.event;

public interface IEventBus {
    void register(Object listener);
    void unregister(Object listener);

    void post(Object event);
}
