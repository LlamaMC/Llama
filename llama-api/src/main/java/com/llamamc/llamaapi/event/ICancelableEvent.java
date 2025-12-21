package com.llamamc.llamaapi.event;

public interface ICancelableEvent extends IEvent {

    void cancel(boolean cancel);
    boolean canceled();

}
