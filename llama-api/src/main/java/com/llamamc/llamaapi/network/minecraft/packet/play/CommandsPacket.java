package com.llamamc.llamaapi.network.minecraft.packet.play;

import com.llamamc.llamaapi.network.protocol.IPacket;

import java.util.HashSet;
import java.util.List;

/**
 * The clientbound commands packet: a root with one literal child per name, so the client can tab-complete them. The
 * names are sent in the given order. A caller passes command names only, never aliases.
 */
public record CommandsPacket(List<String> commandNames) implements IPacket {
    public CommandsPacket {
        if (commandNames == null) {
            throw new IllegalArgumentException("Command names must not be null");
        }

        var distinct = new HashSet<String>();
        for (var name : commandNames) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Command name must not be blank");
            }
            if (!distinct.add(name)) {
                throw new IllegalArgumentException("Command name is listed twice: " + name);
            }
        }
        commandNames = List.copyOf(commandNames);
    }
}
