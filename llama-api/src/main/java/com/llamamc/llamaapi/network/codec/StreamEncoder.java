package com.llamamc.llamaapi.network.codec;

@FunctionalInterface
public interface StreamEncoder<B, V> {
    void encode(B output, V value);
}
