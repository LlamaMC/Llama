package com.llamamc.llamaapi.command;

import java.util.List;

/**
 * Commands run on the thread that calls {@link #dispatch}, not on the tick thread; use the scheduler for tick work.
 */
public interface ICommandRegistry {

    /**
     * Registers the commands of a handler on behalf of an owner. The handler is either an {@link ICommand} with a
     * class-level {@link Command}, or any object whose public methods carry {@link Command}. Each annotated method is
     * one command. If any label is already taken, nothing is registered and an {@link IllegalStateException} is
     * thrown. An invalid handler or method throws an {@link IllegalArgumentException}.
     */
    void register(Object owner, Object handler);

    /** Removes every command registered by the owner. Returns true if any command was removed. */
    boolean unregister(Object owner);

    /**
     * Runs the command matching the first word of the line, after the sender passes the command's permission check.
     * Returns false if no command matches. A denied sender gets a reply and the line still counts as matched.
     */
    boolean dispatch(ICommandSender sender, String line);

    /**
     * Returns the names of the registered commands, sorted, each once. Aliases are not included. The names are in the
     * lowercase form that {@link #dispatch} matches.
     */
    List<String> commandNames();

}
