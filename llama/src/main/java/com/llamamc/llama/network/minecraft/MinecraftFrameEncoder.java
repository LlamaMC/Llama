package com.llamamc.llama.network.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

final class MinecraftFrameEncoder extends MessageToByteEncoder<ByteBuf> {
    @Override
    protected void encode(ChannelHandlerContext context, ByteBuf packet, ByteBuf output) {
        VarInts.write(output, packet.readableBytes());
        output.writeBytes(packet, packet.readerIndex(), packet.readableBytes());
    }
}
