package com.llamamc.llama.command;

import com.llamamc.llamaapi.command.ICommandSender;
import com.llamamc.llamaapi.component.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConsoleSender implements ICommandSender {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConsoleSender.class);
    private static final String NAME = "CONSOLE";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean hasPermission(String permission) {
        if (permission == null) {
            throw new IllegalArgumentException("Permission must not be null");
        }
        return true;
    }

    @Override
    public void sendMessage(Component message) {
        if (message == null) {
            throw new IllegalArgumentException("Message must not be null");
        }
        LOGGER.info("{}", message.text());
    }
}
