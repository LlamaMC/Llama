package com.llamamc.llamaapi.network.codec;

@FunctionalInterface
public interface StreamDecoder<B, V> {
    V decode(B input);
}
