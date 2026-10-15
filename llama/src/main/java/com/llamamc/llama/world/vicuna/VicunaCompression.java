package com.llamamc.llama.world.vicuna;

enum VicunaCompression {
    NONE(0);

    private final int id;

    VicunaCompression(int id) {
        this.id = id;
    }

    int id() {
        return id;
    }

    static VicunaCompression byId(int id) {
        if (id == NONE.id) {
            return NONE;
        }

        throw new IllegalArgumentException("Unsupported VICUÑA compression: " + id);
    }
}
