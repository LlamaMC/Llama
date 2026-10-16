package com.llamamc.llamaapi.command;

import com.llamamc.llamaapi.component.Component;

public interface ICommandSender {

    String name();

    boolean hasPermission(String permission);

    void sendMessage(Component message);

}
