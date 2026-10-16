package com.llamamc.llama.network.minecraft.protocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaletteModeTest {
    @Test
    void choosesTheModeAtTheBoundaries() {
        assertEquals(PaletteMode.SINGLE, PaletteMode.select(1));
        assertEquals(PaletteMode.INDIRECT, PaletteMode.select(2));
        assertEquals(PaletteMode.INDIRECT, PaletteMode.select(16));
        assertEquals(PaletteMode.INDIRECT, PaletteMode.select(17));
        assertEquals(PaletteMode.INDIRECT, PaletteMode.select(256));
        assertEquals(PaletteMode.DIRECT, PaletteMode.select(257));
    }

    @Test
    void rejectsASectionWithoutStates() {
        assertThrows(IllegalArgumentException.class, () -> PaletteMode.select(0));
    }

    @Test
    void indirectBitsAreAtLeastFourAndCoverTheLocalPalette() {
        assertEquals(4, PaletteMode.indirectBits(2));
        assertEquals(4, PaletteMode.indirectBits(16));
        assertEquals(5, PaletteMode.indirectBits(17));
        assertEquals(5, PaletteMode.indirectBits(32));
        assertEquals(6, PaletteMode.indirectBits(33));
        assertEquals(8, PaletteMode.indirectBits(256));
    }

    @Test
    void indirectBitsRejectCountsOutsideTheIndirectRange() {
        assertThrows(IllegalArgumentException.class, () -> PaletteMode.indirectBits(1));
        assertThrows(IllegalArgumentException.class, () -> PaletteMode.indirectBits(257));
    }

    @Test
    void directBitsAreTheWidthOfTheHighestGlobalId() {
        assertEquals(8, PaletteMode.directBits(255));
        assertEquals(9, PaletteMode.directBits(256));
        assertEquals(15, PaletteMode.directBits(32_767));
        assertEquals(16, PaletteMode.directBits(32_768));
        assertEquals(16, PaletteMode.directBits(35_722));
    }

    @Test
    void directBitsRejectANegativeId() {
        assertThrows(IllegalArgumentException.class, () -> PaletteMode.directBits(-1));
    }
}
