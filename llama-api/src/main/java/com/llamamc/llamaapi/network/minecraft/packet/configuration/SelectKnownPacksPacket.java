package com.llamamc.llamaapi.network.minecraft.packet.configuration;

import com.llamamc.llamaapi.network.protocol.IPacket;

import java.util.List;

public record SelectKnownPacksPacket(List<KnownPack> packs) implements IPacket {
    public SelectKnownPacksPacket {
        packs = List.copyOf(packs);
    }
}
