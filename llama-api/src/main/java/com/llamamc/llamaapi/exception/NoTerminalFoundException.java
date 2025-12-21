package com.llamamc.llamaapi.exception;

public class NoTerminalFoundException extends RuntimeException {

    public NoTerminalFoundException(String message) {
        super(message);
    }
}