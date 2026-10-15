package com.llamamc.llamaapi.network.minecraft.packet.configuration;

import com.llamamc.llamaapi.network.protocol.IPacket;

import java.util.List;

public record KnownPacksSelectedPacket(List<KnownPack> packs) implements IPacket {
    public KnownPacksSelectedPacket {
        packs = List.copyOf(packs);
    }
}
