package com.llamamc.llama.tick;

/**
 * Thrown into a tick thread that was abandoned by the watchdog, to unwind it as soon as it returns into server code.
 * It is an {@link Error} so ordinary {@code catch (Exception e)} blocks in plugin code do not swallow it.
 */
public final class AbandonedThreadError extends Error {
    public AbandonedThreadError() {
        super("This tick thread was abandoned by the watchdog", null, false, false);
    }
}
