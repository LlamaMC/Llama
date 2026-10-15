package com.llamamc.llama;

/**
 * Process exit codes. {@code start.sh} restarts the server when it exits with {@link #RESTART}.
 */
public enum ExitCode {
    STOP(0),
    ERROR(1),
    RESTART(75);

    private final int code;

    ExitCode(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }
}
