package com.llamamc.llamaapi.network.minecraft.packet.configuration;

import com.llamamc.llamaapi.network.protocol.IPacket;

import java.util.Arrays;

public record CustomPayloadPacket(String channel, byte[] data) implements IPacket {
    public CustomPayloadPacket {
        data = Arrays.copyOf(data, data.length);
    }

    @Override
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }
}
