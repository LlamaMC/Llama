package com.llamamc.llama.player;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.concurrent.LlamaFuture;
import com.llamamc.llamaapi.concurrent.LlamaPromise;
import com.llamamc.llamaapi.network.minecraft.packet.play.SystemChatPacket;
import com.llamamc.llamaapi.permission.IPermissionService;
import com.llamamc.llamaapi.player.IPlayer;
import com.llamamc.llamaapi.player.PlayerSnapshot;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NetworkPlayer implements IPlayer {
    private final UUID uniqueId;
    private final String name;
    private final String clientBrand;
    private final IPlayerConnection connection;
    private final IPermissionService permissions;
    // Set once, by the first quit. The flag makes PlayerQuitEvent fire once, however many close signals arrive.
    private final AtomicBoolean quitting = new AtomicBoolean();

    public NetworkPlayer(UUID uniqueId, String name, String clientBrand, IPlayerConnection connection,
                         IPermissionService permissions) {
        if (uniqueId == null) {
            throw new IllegalArgumentException("Player UUID must not be null");
        }
        if (name == null) {
            throw new IllegalArgumentException("Player name must not be null");
        }
        if (clientBrand == null) {
            throw new IllegalArgumentException("Client brand must not be null");
        }
        if (connection == null) {
            throw new IllegalArgumentException("Player connection must not be null");
        }
        if (permissions == null) {
            throw new IllegalArgumentException("Permission service must not be null");
        }

        this.uniqueId = uniqueId;
        this.name = name;
        this.clientBrand = clientBrand;
        this.connection = connection;
        this.permissions = permissions;
    }

    @Override
    public UUID uniqueId() {
        return uniqueId;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String clientBrand() {
        return clientBrand;
    }

    @Override
    public PlayerSnapshot snapshot() {
        return new PlayerSnapshot(uniqueId, name, clientBrand);
    }

    @Override
    public LlamaFuture<Boolean> hasPermission(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("Permission must not be null");
        }

        var promise = new LlamaPromise<Boolean>();
        try {
            promise.complete(permissions.hasPermission(uniqueId, permission));
        } catch (RuntimeException error) {
            promise.fail(error);
        }
        return promise.future();
    }

    @Override
    public void kick(Component reason) {
        if (reason == null) {
            throw new IllegalArgumentException("Kick reason must not be null");
        }
        connection.disconnect(reason);
    }

    @Override
    public void sendMessage(Component message) {
        if (message == null) {
            throw new IllegalArgumentException("Message must not be null");
        }
        connection.send(new SystemChatPacket(message, false));
    }

    IPlayerConnection connection() {
        return connection;
    }

    boolean beginQuit() {
        return quitting.compareAndSet(false, true);
    }

    boolean isQuitting() {
        return quitting.get();
    }
}
