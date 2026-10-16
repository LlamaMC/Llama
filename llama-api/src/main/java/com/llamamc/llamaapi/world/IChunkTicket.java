package com.llamamc.llamaapi.world;

/**
 * Keeps a chunk loaded until it is closed. Closing releases the ticket. Closing it again does nothing.
 * Callable from any thread and never blocks.
 */
public interface IChunkTicket extends AutoCloseable {

    @Override
    void close();
}
