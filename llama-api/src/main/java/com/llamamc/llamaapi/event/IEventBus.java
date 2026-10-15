package com.llamamc.llamaapi.event;

import java.util.function.Consumer;

public interface IEventBus {

    <E extends IEvent> IRegistration on(Class<E> type, Consumer<? super E> handler);

    <E extends IEvent> IRegistration on(Class<E> type, EventPriority priority, Consumer<? super E> handler);

    // Handlers run inline on the calling thread, so a slow handler delays the poster.
    void post(IEvent event);

}
