package com.llamamc.llamaapi.network.protocol;

public interface IUnboundProtocol {
    IProtocolInfo bind(Object contextWrapper, Object context);
}
