package com.llamamc.llamaapi;

import java.util.logging.Logger;

public interface ILlamaRuntime extends AutoCloseable {

    void start();

    void shutdown();
    boolean isRunning();

    Logger logger();

    @Override
    void close();

}