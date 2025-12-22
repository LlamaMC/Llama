package com.llamamc.llamaapi.network;

public interface INetworkListener {

    String id();

    InterceptorRegistry interceptors();

    //TODO: Protocol Registry & Stage Registry

}
