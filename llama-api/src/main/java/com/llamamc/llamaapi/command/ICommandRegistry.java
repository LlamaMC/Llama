package com.llamamc.llamaapi.command;

/**
 * Commands run on the thread that calls {@link #dispatch(String)}, not on the tick thread; use the scheduler for
 * tick work. The permission string on {@link Command} is not enforced yet.
 */
public interface ICommandRegistry {

    /**
     * Registers the command under the name and aliases of its {@link Command} annotation. If any label is already
     * taken, nothing is registered and an {@link IllegalStateException} is thrown.
     */
    void register(ICommand command);

    /** Removes every label of the command. Returns true if any label was removed. */
    boolean unregister(ICommand command);

    /** Runs the command matching the first word of the line. Returns false if no command matches. */
    boolean dispatch(String line);

}
