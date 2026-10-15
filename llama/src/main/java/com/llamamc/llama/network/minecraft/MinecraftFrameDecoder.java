package com.llamamc.llama.network.minecraft;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;

import java.util.List;

final class MinecraftFrameDecoder extends ByteToMessageDecoder {
    private static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;

    @Override
    protected void decode(ChannelHandlerContext context, ByteBuf input, List<Object> output) {
        input.markReaderIndex();
        var frameLength = VarInts.read(input);

        if (frameLength == null) {
            input.resetReaderIndex();
            return;
        }
        if (frameLength < 0 || frameLength > MAX_FRAME_BYTES) {
            throw new DecoderException("Invalid Minecraft packet length: " + frameLength);
        }
        if (input.readableBytes() < frameLength) {
            input.resetReaderIndex();
            return;
        }

        output.add(input.readRetainedSlice(frameLength));
    }
}
