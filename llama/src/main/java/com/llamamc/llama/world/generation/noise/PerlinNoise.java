package com.llamamc.llama.world.generation.noise;

final class PerlinNoise implements Noise {
    private static final int[][] GRADIENTS = {
            {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
            {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
            {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1},
            {1, 1, 0}, {0, -1, 1}, {-1, 1, 0}, {0, -1, -1}
    };
    private static final double WRAP = 33554432.0;
    private static final double HALF_WRAP = Math.nextDown(16777216.0);

    private final byte[] permutations = new byte[256];
    private final double offsetX;
    private final double offsetY;
    private final double offsetZ;

    PerlinNoise(XoroshiroRandom random) {
        offsetX = random.nextDouble() * 256.0;
        offsetY = random.nextDouble() * 256.0;
        offsetZ = random.nextDouble() * 256.0;
        for (var index = 0; index < permutations.length; index++) {
            permutations[index] = (byte) index;
        }
        for (var index = 0; index < permutations.length; index++) {
            var target = index + random.nextInt(256 - index);
            var value = permutations[index];
            permutations[index] = permutations[target];
            permutations[target] = value;
        }
    }

    @Override
    public float sample(double rawX, double rawY, double rawZ) {
        var x = wrap(rawX) + offsetX;
        var y = wrap(rawY) + offsetY;
        var z = wrap(rawZ) + offsetZ;
        var floorX = floor(x);
        var floorY = floor(y);
        var floorZ = floor(z);
        var relativeX = (float) (x - floorX);
        var relativeY = (float) (y - floorY);
        var relativeZ = (float) (z - floorZ);
        return sample(floorX, floorY, floorZ, relativeX, relativeY, relativeZ);
    }

    private float sample(int x, int y, int z, float relativeX, float relativeY, float relativeZ) {
        var x0 = permute(x);
        var x1 = permute(x + 1);
        var xy00 = permute(x0 + y);
        var xy01 = permute(x0 + y + 1);
        var xy10 = permute(x1 + y);
        var xy11 = permute(x1 + y + 1);
        var d000 = gradient(permute(xy00 + z), relativeX, relativeY, relativeZ);
        var d100 = gradient(permute(xy10 + z), relativeX - 1.0F, relativeY, relativeZ);
        var d010 = gradient(permute(xy01 + z), relativeX, relativeY - 1.0F, relativeZ);
        var d110 = gradient(permute(xy11 + z), relativeX - 1.0F, relativeY - 1.0F, relativeZ);
        var d001 = gradient(permute(xy00 + z + 1), relativeX, relativeY, relativeZ - 1.0F);
        var d101 = gradient(permute(xy10 + z + 1), relativeX - 1.0F, relativeY, relativeZ - 1.0F);
        var d011 = gradient(permute(xy01 + z + 1), relativeX, relativeY - 1.0F, relativeZ - 1.0F);
        var d111 = gradient(permute(xy11 + z + 1), relativeX - 1.0F, relativeY - 1.0F, relativeZ - 1.0F);
        return lerp3(smoothstep(relativeX), smoothstep(relativeY), smoothstep(relativeZ),
                d000, d100, d010, d110, d001, d101, d011, d111);
    }

    private int permute(int value) {
        return permutations[value & 0xFF] & 0xFF;
    }

    private static float gradient(int hash, float x, float y, float z) {
        var gradient = GRADIENTS[hash & 15];
        return gradient[0] * x + gradient[1] * y + gradient[2] * z;
    }

    private static float smoothstep(float value) {
        return value * value * value * (value * (value * 6.0F - 15.0F) + 10.0F);
    }

    private static float lerp(float alpha, float from, float to) {
        return from + alpha * (to - from);
    }

    private static float lerp2(float x, float y, float x0y0, float x1y0, float x0y1, float x1y1) {
        return lerp(y, lerp(x, x0y0, x1y0), lerp(x, x0y1, x1y1));
    }

    private static float lerp3(float x, float y, float z, float x0y0z0, float x1y0z0, float x0y1z0,
                               float x1y1z0, float x0y0z1, float x1y0z1, float x0y1z1, float x1y1z1) {
        return lerp(z, lerp2(x, y, x0y0z0, x1y0z0, x0y1z0, x1y1z0),
                lerp2(x, y, x0y0z1, x1y0z1, x0y1z1, x1y1z1));
    }

    private static int floor(double value) {
        var integer = (int) value;
        return value < integer ? integer - 1 : integer;
    }

    private static double wrap(double value) {
        if (value >= -HALF_WRAP && value < HALF_WRAP) {
            return value;
        }
        return value - Math.floor(value / WRAP + 0.5) * WRAP;
    }
}
