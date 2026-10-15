package com.llamamc.llama.world.generation.density;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class MinecraftDensityRouterTest {
    @Test
    void compilesAndSamplesTheCompleteOverworldDensityGraph() {
        var density = new MinecraftDensityRouter(123456789L).overworld();

        assertTrue(Float.isFinite(density.sample(0, 64, 0)));
        assertTrue(Float.isFinite(density.sample(173, -32, -291)));
        assertTrue(Float.isFinite(density.sample(-1024, 200, 2048)));
    }
}
