package com.llamamc.llama.world.generation.noise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

final class XoroshiroRandom {
    private static final long GOLDEN_RATIO = -7046029254386353131L;
    private static final long SILVER_RATIO = 7640891576956012809L;
    private static final double DOUBLE_UNIT = 0x1.0p-53;

    private long low;
    private long high;

    XoroshiroRandom(long seed) {
        this(upgrade(seed));
    }

    XoroshiroRandom(Seed128 seed) {
        low = seed.low();
        high = seed.high();
        if ((low | high) != 0L) {
            return;
        }

        low = GOLDEN_RATIO;
        high = SILVER_RATIO;
    }

    long nextLong() {
        var first = low;
        var second = high;
        var result = Long.rotateLeft(first + second, 17) + first;
        second ^= first;
        low = Long.rotateLeft(first, 49) ^ second ^ second << 21;
        high = Long.rotateLeft(second, 28);
        return result;
    }

    int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("Bound must be positive");
        }

        var bits = Integer.toUnsignedLong((int) nextLong());
        var multiplied = bits * bound;
        var fractional = multiplied & 0xFFFFFFFFL;
        if (fractional < bound) {
            var unbiasedStart = Integer.remainderUnsigned(~bound + 1, bound);
            while (fractional < unbiasedStart) {
                bits = Integer.toUnsignedLong((int) nextLong());
                multiplied = bits * bound;
                fractional = multiplied & 0xFFFFFFFFL;
            }
        }

        return (int) (multiplied >> 32);
    }

    double nextDouble() {
        return (nextLong() >>> 11) * DOUBLE_UNIT;
    }

    PositionalFactory forkPositional() {
        return new PositionalFactory(nextLong(), nextLong());
    }

    private static Seed128 upgrade(long seed) {
        var low = seed ^ SILVER_RATIO;
        var high = low + GOLDEN_RATIO;
        return new Seed128(mixStafford13(low), mixStafford13(high));
    }

    private static long mixStafford13(long value) {
        value = (value ^ value >>> 30) * -4658895280553007687L;
        value = (value ^ value >>> 27) * -7723592293110705685L;
        return value ^ value >>> 31;
    }

    record PositionalFactory(long low, long high) {
        XoroshiroRandom fromHash(String name) {
            return new XoroshiroRandom(hash(name).xor(new Seed128(low, high)));
        }

        private static Seed128 hash(String name) {
            try {
                var digest = MessageDigest.getInstance("MD5").digest(name.getBytes(StandardCharsets.UTF_8));
                var buffer = ByteBuffer.wrap(digest).order(ByteOrder.BIG_ENDIAN);
                return new Seed128(buffer.getLong(), buffer.getLong());
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException("MD5 is unavailable", error);
            }
        }
    }
}
