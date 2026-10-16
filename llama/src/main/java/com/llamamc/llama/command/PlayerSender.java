package com.llamamc.llama.command;

import com.llamamc.llamaapi.command.ICommandSender;
import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.permission.IPermissionService;
import com.llamamc.llamaapi.player.IPlayer;

public final class PlayerSender implements ICommandSender {
    private final IPlayer player;
    private final IPermissionService permissions;

    public PlayerSender(IPlayer player, IPermissionService permissions) {
        if (player == null) {
            throw new IllegalArgumentException("Player must not be null");
        }
        if (permissions == null) {
            throw new IllegalArgumentException("Permission service must not be null");
        }
        this.player = player;
        this.permissions = permissions;
    }

    @Override
    public String name() {
        return player.name();
    }

    @Override
    public boolean hasPermission(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("Permission must not be null");
        }
        return permissions.hasPermission(player.uniqueId(), permission);
    }

    @Override
    public void sendMessage(Component message) {
        if (message == null) {
            throw new IllegalArgumentException("Message must not be null");
        }
        player.sendMessage(message);
    }
}
