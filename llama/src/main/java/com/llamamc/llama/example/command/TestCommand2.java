package com.llamamc.llama.example.command;

import com.llamamc.llamaapi.command.Arg;
import com.llamamc.llamaapi.command.ArgumentType;
import com.llamamc.llamaapi.command.Command;
import com.llamamc.llamaapi.command.ICommandContext;
import com.llamamc.llamaapi.player.IPlayer;

public class TestCommand2 {

    @Command(name = "test")
    public void test(ICommandContext context, @Arg(name = "player", type = ArgumentType.PLAYER_NAME, position = 0) IPlayer player) {

    }

}
