package com.llamamc.llama.player;

import com.llamamc.llamaapi.player.IPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class PlayerRegistryTest {
    private static final int READER_THREADS = 4;
    private static final UUID ALICE_ID = UUID.nameUUIDFromBytes("Alice".getBytes(StandardCharsets.UTF_8));

    private final TestPlayers.Permissions permissions = new TestPlayers.Permissions();
    private final PlayerRegistry registry = new PlayerRegistry();

    @Test
    void lookupsFromSeveralThreadsKeepFindingTheStablePlayersWhileOthersChange() throws Exception {
        var stable = IntStream.range(0, 10).mapToObj(index -> player("Stable" + index)).toList();
        stable.forEach(registry::register);
        var writing = new AtomicBoolean(true);
        ExecutorService readers = Executors.newFixedThreadPool(READER_THREADS);
        try {
            List<Future<Void>> results = IntStream.range(0, READER_THREADS)
                    .mapToObj(index -> readers.submit(() -> readUntilDone(stable, writing)))
                    .toList();
            for (var index = 0; index < 50; index++) {
                var transientPlayer = player("Transient" + index);
                registry.register(transientPlayer);
                assertTrue(registry.remove(transientPlayer));
            }
            writing.set(false);
            for (var result : results) {
                result.get();
            }
        } finally {
            readers.shutdownNow();
        }
        assertEquals(stable.size(), registry.online().size());
    }

    @Test
    void registeredPlayersAreFoundByUuidAndByNameIgnoringCase() {
        var alice = player(ALICE_ID, "Alice");
        registry.register(alice);

        assertSame(alice, registry.byUuid(ALICE_ID).orElseThrow());
        assertSame(alice, registry.byName("ALICE").orElseThrow());
        assertEquals(Optional.empty(), registry.byName("Bob"));
    }

    @Test
    void aSecondRegistrationWithTheSameUuidIsRefused() {
        var first = player(ALICE_ID, "Alice");
        var second = player(ALICE_ID, "Alice");
        registry.register(first);

        assertThrows(IllegalStateException.class, () -> registry.register(second));
        assertSame(first, registry.byUuid(ALICE_ID).orElseThrow());
        assertEquals(1, registry.online().size());
    }

    @Test
    void removeTakesOnlyTheRegisteredSession() {
        var first = player(ALICE_ID, "Alice");
        var other = player(ALICE_ID, "Alice");
        registry.register(first);

        assertFalse(registry.remove(other));
        assertTrue(registry.remove(first));
        assertFalse(registry.remove(first));
        assertEquals(Optional.empty(), registry.byUuid(ALICE_ID));
    }

    @Test
    void onlineReturnsAnImmutableSnapshot() {
        var alice = player("Alice");
        registry.register(alice);
        List<IPlayer> snapshot = registry.online();

        registry.register(player("Bob"));

        assertEquals(List.of(alice), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(player("Carol")));
    }

    private Void readUntilDone(List<NetworkPlayer> stable, AtomicBoolean writing) {
        do {
            assertTrue(registry.online().containsAll(stable));
            for (var player : stable) {
                assertSame(player, registry.byUuid(player.uniqueId()).orElseThrow());
                assertSame(player, registry.byName(player.name()).orElseThrow());
            }
        } while (writing.get());
        return null;
    }

    private NetworkPlayer player(String name) {
        return player(UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)), name);
    }

    private NetworkPlayer player(UUID uniqueId, String name) {
        return new NetworkPlayer(uniqueId, name, "vanilla", new TestPlayers.Connection(), permissions);
    }
}
