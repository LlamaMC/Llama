package com.llamamc.llamaapi.command;

import com.llamamc.llamaapi.component.Component;

import java.util.List;

public interface ICommandContext {

    String label();

    List<String> arguments();

    ICommandSender sender();

    default void reply(Component message) {
        sender().sendMessage(message);
    }

}
