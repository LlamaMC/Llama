package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.network.protocol.IPacket;

public record SystemChatPacket(Component content, boolean overlay) implements IPacket {
    public SystemChatPacket {
        if (content == null) {
            throw new IllegalArgumentException("System chat content must not be null");
        }
    }
}
