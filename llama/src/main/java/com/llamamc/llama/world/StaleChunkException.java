package com.llamamc.llama.world;

import java.io.IOException;

final class StaleChunkException extends IOException {
    StaleChunkException(int storedVersion, int expectedVersion) {
        super("Chunk generation version " + storedVersion + " does not match " + expectedVersion);
    }
}
