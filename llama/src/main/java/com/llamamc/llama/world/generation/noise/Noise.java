package com.llamamc.llama.world.generation.noise;

@FunctionalInterface
public interface Noise {
    float sample(double x, double y, double z);
}
