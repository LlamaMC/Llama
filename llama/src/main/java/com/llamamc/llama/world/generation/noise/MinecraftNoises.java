package com.llamamc.llama.world.generation.noise;

public final class MinecraftNoises {
    private MinecraftNoises() {
    }

    public static Noise normal(long worldSeed, String key, NormalNoise.Parameters parameters) {
        return NormalNoise.minecraft(worldSeed, key, parameters);
    }

    public static Noise blended(long worldSeed, double xzScale, double yScale, double xzFactor,
                                double yFactor, double smearScaleMultiplier) {
        return new BlendedNoise(worldSeed, xzScale, yScale, xzFactor, yFactor, smearScaleMultiplier);
    }
}
