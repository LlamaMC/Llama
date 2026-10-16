package com.llamamc.llamaapi.scheduler;

public interface ITaskHandle {

    // Safe from any thread. Calling it again has no further effect.
    void cancel();

    boolean isCancelled();

}
