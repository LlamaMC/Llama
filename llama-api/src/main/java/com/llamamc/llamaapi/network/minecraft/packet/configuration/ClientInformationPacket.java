package com.llamamc.llamaapi.network.minecraft.packet.configuration;

import com.llamamc.llamaapi.network.protocol.IPacket;

public record ClientInformationPacket(String language, int viewDistance, int chatVisibility, boolean chatColors,
                                      int modelCustomisation, int mainHand, boolean textFilteringEnabled,
                                      boolean serverListingAllowed, int particleStatus) implements IPacket {
}
