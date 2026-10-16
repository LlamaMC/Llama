package com.llamamc.llama.world;

// A dedicated type lets chunkNow() recognise a worker thread and refuse to block it.
final class WorldWorkerThread extends Thread {
    WorldWorkerThread(Runnable task, String name) {
        super(task, name);
    }
}
