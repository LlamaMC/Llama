package com.llamamc.llamaapi.network;

import java.util.List;

public interface InterceptorRegistry {

    void registerInbound(int priority, InboundInterceptor interceptor);

    void unregisterInbound(InboundInterceptor interceptor);

    List<InboundInterceptor> inbound();

}
