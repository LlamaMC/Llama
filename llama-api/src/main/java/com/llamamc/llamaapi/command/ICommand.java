package com.llamamc.llamaapi.command;

import java.util.List;

public interface ICommand {

    void execute(ICommandContext context);

    default List<String> suggest(ICommandContext context) {
        return List.of();
    }

}
