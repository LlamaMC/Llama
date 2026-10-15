package com.llamamc.llama.world.generation.noise;

record Seed128(long low, long high) {
    Seed128 xor(Seed128 other) {
        return new Seed128(low ^ other.low, high ^ other.high);
    }
}
