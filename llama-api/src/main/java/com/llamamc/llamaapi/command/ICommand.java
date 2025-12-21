package com.llamamc.llamaapi.command;

import java.util.List;

public interface ICommand {

    void execute(ICommandContext context);

    List<String> suggest(ICommandContext context);

}
