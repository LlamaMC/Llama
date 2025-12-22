package com.llamamc.llama.network.codec;

import com.llamamc.llamaapi.network.codec.StreamCodec;
import com.llamamc.llamaapi.network.protocol.IPacket;
import com.llamamc.llamaapi.network.protocol.PacketType;
import io.netty.buffer.ByteBuf;

import java.util.function.Function;

public final class EnvelopeDispatchCodec implements StreamCodec<ByteBuf, IPacket<?>> {

    private static final int CORE_CHANNEL = 0;
    private static final int EXTENSION_CHANNEL = 1;

    private final StreamCodec<ByteBuf, IPacket<?>> coreCodec;
    private final ExtensionDispatch[] extensionCodecs;
    private final Function<IPacket<?>, PacketType> typeGetter;

    private EnvelopeDispatchCodec(StreamCodec<ByteBuf, IPacket<?>> coreCodec, ExtensionDispatch[] extensionCodecs, Function<IPacket<?>, PacketType> typeGetter) {
        this.coreCodec = coreCodec;
        this.extensionCodecs = extensionCodecs;
        this.typeGetter = typeGetter;
    }

    @Override
    public IPacket<?> decode(ByteBuf input) {
        return null;
    }

    @Override
    public void encode(ByteBuf output, IPacket<?> value) {

    }

    final class ExtensionDispatch implements StreamCodec<ByteBuf, IPacket<?>> {
        @Override
        public IPacket<?> decode(ByteBuf input) {
            return null;
        }

        @Override
        public void encode(ByteBuf output, IPacket<?> value) {

        }
    }
}
