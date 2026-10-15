package com.llamamc.llama.world.generation.noise;

final class SmearedPerlinNoise implements Noise {
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
    private final double fudgeYScale;

    SmearedPerlinNoise(XoroshiroRandom random, double fudgeYScale) {
        this.fudgeYScale = fudgeYScale;
        offsetX = random.nextDouble() * 256.0;
        offsetY = random.nextDouble() * 256.0;
        offsetZ = random.nextDouble() * 256.0;
        for (var index = 0; index < 256; index++) {
            permutations[index] = (byte) index;
        }
        for (var index = 0; index < 256; index++) {
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
        var originalRelativeY = y - floorY;
        var relativeZ = (float) (z - floorZ);
        var fudgedY = (float) (originalRelativeY - fudge(rawY, originalRelativeY));
        return sample(floorX, floorY, floorZ, relativeX, fudgedY, relativeZ, (float) originalRelativeY);
    }

    private double fudge(double originalY, double relativeY) {
        var limit = originalY >= 0.0 && originalY < relativeY ? originalY : relativeY;
        return Math.floor(limit / fudgeYScale + 1.0E-7F) * fudgeYScale;
    }

    private float sample(int x, int y, int z, float rx, float ry, float rz, float originalY) {
        var x0 = permute(x);
        var x1 = permute(x + 1);
        var xy00 = permute(x0 + y);
        var xy01 = permute(x0 + y + 1);
        var xy10 = permute(x1 + y);
        var xy11 = permute(x1 + y + 1);
        var d000 = gradient(permute(xy00 + z), rx, ry, rz);
        var d100 = gradient(permute(xy10 + z), rx - 1.0F, ry, rz);
        var d010 = gradient(permute(xy01 + z), rx, ry - 1.0F, rz);
        var d110 = gradient(permute(xy11 + z), rx - 1.0F, ry - 1.0F, rz);
        var d001 = gradient(permute(xy00 + z + 1), rx, ry, rz - 1.0F);
        var d101 = gradient(permute(xy10 + z + 1), rx - 1.0F, ry, rz - 1.0F);
        var d011 = gradient(permute(xy01 + z + 1), rx, ry - 1.0F, rz - 1.0F);
        var d111 = gradient(permute(xy11 + z + 1), rx - 1.0F, ry - 1.0F, rz - 1.0F);
        return lerp3(smoothstep(rx), smoothstep(originalY), smoothstep(rz),
                d000, d100, d010, d110, d001, d101, d011, d111);
    }

    private int permute(int value) {
        return permutations[value & 255] & 255;
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

    private static float lerp2(float x, float y, float a, float b, float c, float d) {
        return lerp(y, lerp(x, a, b), lerp(x, c, d));
    }

    private static float lerp3(float x, float y, float z, float a, float b, float c, float d,
                               float e, float f, float g, float h) {
        return lerp(z, lerp2(x, y, a, b, c, d), lerp2(x, y, e, f, g, h));
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
