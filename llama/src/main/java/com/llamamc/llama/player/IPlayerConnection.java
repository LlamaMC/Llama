package com.llamamc.llama.player;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.network.protocol.IPacket;

import java.util.List;

/**
 * The network side of one player. The tick thread calls these methods, so every implementation hands the work to
 * the connection's event loop.
 */
public interface IPlayerConnection {

    /** Sends a play packet. Nothing is sent once the connection has left the play state or has closed. */
    void send(IPacket packet);

    /** Sends the play disconnect with the reason, then closes the connection. */
    void disconnect(Component reason);

    /** Sends the login or configuration disconnect with the reason, then closes the connection. */
    void denyJoin(Component reason);

    /**
     * Enters the play state and sends the join packets. The callback runs on the network thread once the last packet
     * has been flushed. It does not run when the connection closed first.
     */
    void join(List<IPacket> packets, Runnable whenSent);

}
