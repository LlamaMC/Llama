package com.llamamc.llamaapi.event;

public interface IRegistration extends AutoCloseable {

    /**
     * After close returns, the handler is not started again. A post already in progress on another
     * thread may still call it once.
     */
    @Override
    void close();

}
