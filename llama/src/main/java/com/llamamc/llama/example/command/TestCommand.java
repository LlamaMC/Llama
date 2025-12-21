package com.llamamc.llama.example.command;

import com.llamamc.llamaapi.command.Command;
import com.llamamc.llamaapi.command.ICommand;
import com.llamamc.llamaapi.command.ICommandContext;

import java.util.List;

@Command(
        name = "test",
        description = "test command",
        permission = "test.test",
        aliases = {
                "t"
        }
)
public class TestCommand implements ICommand {

    @Override
    public void execute(ICommandContext context) {

    }

    @Override
    public List<String> suggest(ICommandContext context) {
        return List.of();
    }
}
