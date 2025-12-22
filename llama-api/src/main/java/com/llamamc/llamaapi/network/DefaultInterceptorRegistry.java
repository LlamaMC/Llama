package com.llamamc.llamaapi.network;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class DefaultInterceptorRegistry implements InterceptorRegistry {

    private final CopyOnWriteArrayList<Entry> inbound = new CopyOnWriteArrayList<>();

    @Override
    public void registerInbound(int priority, InboundInterceptor interceptor) {
        inbound.add(new Entry(priority, interceptor));
        inbound.sort(Comparator.comparingInt(e -> e.priority));
    }

    @Override
    public void unregisterInbound(InboundInterceptor interceptor) {
        inbound.removeIf(e -> e.interceptor == interceptor);
    }

    @Override
    public List<InboundInterceptor> inbound() {
        return inbound.stream().map(Entry::interceptor).toList();
    }

    record Entry(int priority, InboundInterceptor interceptor) {}
}
