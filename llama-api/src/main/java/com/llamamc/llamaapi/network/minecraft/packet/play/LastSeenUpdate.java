package com.llamamc.llamaapi.network.minecraft.packet.play;

import java.util.BitSet;

public record LastSeenUpdate(int offset, BitSet acknowledged, byte checksum) {
    public LastSeenUpdate {
        if (acknowledged == null) {
            throw new IllegalArgumentException("Acknowledged messages must not be null");
        }

        acknowledged = (BitSet) acknowledged.clone();
    }

    @Override
    public BitSet acknowledged() {
        return (BitSet) acknowledged.clone();
    }
}
