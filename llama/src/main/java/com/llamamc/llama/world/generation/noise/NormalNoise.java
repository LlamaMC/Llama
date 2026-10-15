package com.llamamc.llama.world.generation.noise;

import java.util.ArrayList;
import java.util.List;

public final class NormalNoise implements Noise {
    private static final double PERLIN_DEVIATION = 0.2702247831245211;
    private static final double SECOND_FREQUENCY = 1.0181268882175227;

    private final List<Layer> layers;

    public NormalNoise(long seed, Parameters parameters) {
        this(new XoroshiroRandom(seed), parameters);
    }

    static NormalNoise minecraft(long seed, String key, Parameters parameters) {
        var worldRandom = new XoroshiroRandom(seed).forkPositional();
        return new NormalNoise(worldRandom.fromHash(namespaced(key)), parameters);
    }

    private NormalNoise(XoroshiroRandom random, Parameters parameters) {
        var octaves = createOctaves(parameters);
        var normalization = normalization(octaves);
        var first = random.forkPositional();
        var second = random.forkPositional();
        var created = new ArrayList<Layer>(octaves.size() * 2);
        for (var octave : octaves) {
            var name = "octave_" + octave.index();
            var amplitude = (float) (normalization * octave.amplitude());
            created.add(new Layer(new PerlinNoise(first.fromHash(name)), octave.frequency(), amplitude));
            created.add(new Layer(new PerlinNoise(second.fromHash(name)),
                    octave.frequency() * SECOND_FREQUENCY, amplitude));
        }
        layers = List.copyOf(created);
    }

    private static String namespaced(String key) {
        return key.indexOf(':') >= 0 ? key : "minecraft:" + key;
    }

    @Override
    public float sample(double x, double y, double z) {
        var value = 0.0F;
        for (var layer : layers) {
            value += layer.amplitude() * layer.noise().sample(
                    x * layer.frequency(), y * layer.frequency(), z * layer.frequency());
        }
        return value;
    }

    private static List<Octave> createOctaves(Parameters parameters) {
        var frequency = Math.pow(2.0, parameters.baseOctave());
        var amplitude = parameters.baseAmplitude();
        if (parameters.normalized()) {
            var count = parameters.amplitudeModifiers().length;
            amplitude *= Math.pow(0.5, -(count - 1)) / (Math.pow(0.5, -count) - 1.0);
        }

        var octaves = new ArrayList<Octave>();
        for (var index = 0; index < parameters.amplitudeModifiers().length; index++) {
            var modifier = parameters.amplitudeModifiers()[index];
            if (modifier != 0.0) {
                octaves.add(new Octave(parameters.baseOctave() + index, frequency, amplitude * modifier));
            }
            frequency *= 2.0;
            amplitude *= 0.5;
        }
        return octaves;
    }

    private static double normalization(List<Octave> octaves) {
        var targetAmplitude = 0.0;
        var variance = 0.0;
        for (var octave : octaves) {
            var absolute = Math.abs(octave.amplitude());
            targetAmplitude += absolute;
            var deviation = PERLIN_DEVIATION * absolute;
            variance += deviation * deviation;
        }
        if (variance == 0.0) {
            return 0.0;
        }
        return targetAmplitude / 3.0 / (Math.sqrt(variance) * Math.sqrt(2.0));
    }

    public record Parameters(double baseAmplitude, int baseOctave, boolean normalized,
                             double... amplitudeModifiers) {
        public Parameters {
            if (baseAmplitude <= 0.0) {
                throw new IllegalArgumentException("Base amplitude must be positive");
            }
            if (amplitudeModifiers.length == 0 || amplitudeModifiers.length > 32) {
                throw new IllegalArgumentException("Noise needs between 1 and 32 octaves");
            }
            amplitudeModifiers = amplitudeModifiers.clone();
        }

        @Override
        public double[] amplitudeModifiers() {
            return amplitudeModifiers.clone();
        }
    }

    private record Octave(int index, double frequency, double amplitude) {
    }

    private record Layer(Noise noise, double frequency, float amplitude) {
    }
}
