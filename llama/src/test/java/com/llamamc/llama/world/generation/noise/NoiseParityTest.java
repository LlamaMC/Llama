package com.llamamc.llama.world.generation.noise;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NoiseParityTest {
    @Test
    void xoroshiroMatchesMinecraft26_3() {
        var random = new XoroshiroRandom(123456789L);

        assertEquals(3219654894476264721L, random.nextLong());
        assertEquals(8777179534091651608L, random.nextLong());
    }

    @Test
    void perlinMatchesMinecraft26_3IncludingCoordinateWrapping() {
        var noise = new PerlinNoise(new XoroshiroRandom(123456789L));

        assertEquals(-0.054665625F, noise.sample(0.0, 0.0, 0.0));
        assertEquals(-0.13629448F, noise.sample(12.25, -31.5, 99.75));
        assertEquals(-0.22377227F, noise.sample(-16777217.25, 3.5, 16777216.75));
    }

    @Test
    void oldBlendedNoiseMatchesMinecraft26_3() {
        var noise = MinecraftNoises.blended(123456789L, 0.25, 0.125, 80.0, 160.0, 8.0);

        assertEquals(-0.010050893F, noise.sample(0.0, 0.0, 0.0));
        assertEquals(0.30401397F, noise.sample(12.0, -31.0, 99.0));
        assertEquals(-0.2471744F, noise.sample(-1024.0, 173.0, 2048.0));
    }
}
