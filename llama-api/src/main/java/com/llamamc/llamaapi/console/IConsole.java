package com.llamamc.llamaapi.console;

public interface IConsole {

    void start();
    void exit();
    void handleCommand(String cmd, String[] parts);
    IPrinter getPrinter();

}