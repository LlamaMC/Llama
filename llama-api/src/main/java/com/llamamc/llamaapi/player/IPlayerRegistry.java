package com.llamamc.llamaapi.player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only view of the online players. Every method may be called from any thread. */
public interface IPlayerRegistry {

    /** Returns an immutable list taken when the method is called. It does not change later. */
    List<IPlayer> online();

    Optional<IPlayer> byUuid(UUID uniqueId);

    /** Matches the name ignoring case. */
    Optional<IPlayer> byName(String name);

}
