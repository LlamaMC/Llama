package com.llamamc.llama.world.generation.density;

@FunctionalInterface
public interface DensityFunction {
    float sample(int blockX, int blockY, int blockZ);
}
