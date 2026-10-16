package com.llamamc.llamaapi.world;

/**
 * Why a chunk is held. The type is informational for now: every ticket keeps its chunk loaded the same way.
 */
public enum ChunkTicketType {
    PLAYER,
    PLUGIN,
    GENERATION,
    SYSTEM
}
