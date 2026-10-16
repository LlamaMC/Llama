package com.llamamc.llama.player;

import com.llamamc.llamaapi.player.IPlayer;
import com.llamamc.llamaapi.player.IPlayerRegistry;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Online players keyed by UUID. Writes happen on the tick thread through {@link PlayerLifecycle}. The map is replaced
 * on every change, so a reader on any thread sees a complete map and never a half-updated one.
 */
public final class PlayerRegistry implements IPlayerRegistry {
    private final Object lock = new Object();
    private volatile Map<UUID, NetworkPlayer> players = Map.of();

    /**
     * @throws IllegalStateException if a player with the same UUID is already registered
     */
    void register(NetworkPlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("Player must not be null");
        }

        synchronized (lock) {
            if (players.containsKey(player.uniqueId())) {
                throw new IllegalStateException("Player " + player.uniqueId() + " is already registered");
            }
            var next = new HashMap<>(players);
            next.put(player.uniqueId(), player);
            players = Map.copyOf(next);
        }
    }

    /** Removes the given session only. A different session with the same UUID is left in place. */
    boolean remove(NetworkPlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("Player must not be null");
        }

        synchronized (lock) {
            if (players.get(player.uniqueId()) != player) {
                return false;
            }
            var next = new HashMap<>(players);
            next.remove(player.uniqueId());
            players = Map.copyOf(next);
            return true;
        }
    }

    Optional<NetworkPlayer> session(UUID uniqueId) {
        if (uniqueId == null) {
            throw new IllegalArgumentException("Player UUID must not be null");
        }
        return Optional.ofNullable(players.get(uniqueId));
    }

    // A copy, so the caller can remove players while it walks the list.
    List<NetworkPlayer> sessions() {
        return List.copyOf(players.values());
    }

    @Override
    public List<IPlayer> online() {
        return List.<IPlayer>copyOf(players.values());
    }

    @Override
    public Optional<IPlayer> byUuid(UUID uniqueId) {
        if (uniqueId == null) {
            throw new IllegalArgumentException("Player UUID must not be null");
        }
        return Optional.<IPlayer>ofNullable(players.get(uniqueId));
    }

    @Override
    public Optional<IPlayer> byName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("Player name must not be null");
        }
        return players.values().stream()
                .filter(player -> player.name().equalsIgnoreCase(name))
                .<IPlayer>map(player -> player)
                .findFirst();
    }
}
