package com.llamamc.llama.world.generation.noise;

import java.util.ArrayList;
import java.util.List;

final class BlendedNoise implements Noise {
    private static final double BASE_SCALE = 684.412;

    private final List<Layer> minimum;
    private final List<Layer> maximum;
    private final List<Layer> main;
    private final double xzMultiplier;
    private final double yMultiplier;
    private final double xzFactor;
    private final double yFactor;

    BlendedNoise(long seed, double xzScale, double yScale, double xzFactor, double yFactor,
                 double smearScaleMultiplier) {
        var factory = new XoroshiroRandom(seed).forkPositional();
        var random = factory.fromHash("minecraft:terrain");
        xzMultiplier = BASE_SCALE * xzScale;
        yMultiplier = BASE_SCALE * yScale;
        this.xzFactor = xzFactor;
        this.yFactor = yFactor;
        var limitSmear = yMultiplier * smearScaleMultiplier;
        minimum = createFbm(random, -15, limitSmear, 0.99998474F);
        maximum = createFbm(random, -15, limitSmear, 0.99998474F);
        main = createFbm(random, -7, limitSmear / yFactor, 12.75);
    }

    @Override
    public float sample(double x, double y, double z) {
        var choice = clamp(sample(main, x * xzMultiplier / xzFactor, y * yMultiplier / yFactor,
                z * xzMultiplier / xzFactor) + 0.5F, 0.0F, 1.0F);
        var low = sample(minimum, x * xzMultiplier, y * yMultiplier, z * xzMultiplier);
        var high = sample(maximum, x * xzMultiplier, y * yMultiplier, z * xzMultiplier);
        return low + choice * (high - low);
    }

    private static List<Layer> createFbm(XoroshiroRandom random, int firstOctave, double smearScale,
                                          double valueFactor) {
        var count = -firstOctave + 1;
        var frequency = 1.0;
        valueFactor /= Math.pow(2.0, count) - 1.0;
        var layers = new ArrayList<Layer>(count);
        for (var index = count - 1; index >= 0; index--) {
            layers.add(new Layer(new SmearedPerlinNoise(random, smearScale * frequency),
                    frequency, (float) valueFactor));
            frequency *= 0.5;
            valueFactor *= 2.0;
        }
        return List.copyOf(layers);
    }

    private static float sample(List<Layer> layers, double x, double y, double z) {
        var value = 0.0F;
        for (var layer : layers) {
            value += layer.amplitude() * layer.noise().sample(
                    x * layer.frequency(), y * layer.frequency(), z * layer.frequency());
        }
        return value;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record Layer(Noise noise, double frequency, float amplitude) {
    }
}
