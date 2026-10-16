package com.llamamc.llama.network.minecraft.protocol;

/**
 * Wire format of one block-state container. The mode depends only on the number of distinct states in a section.
 */
enum PaletteMode {
    SINGLE,
    INDIRECT,
    DIRECT;

    static final int MAX_INDIRECT_STATES = 256;
    private static final int MIN_INDIRECT_BITS = 4;

    static PaletteMode select(int distinctStates) {
        if (distinctStates < 1) {
            throw new IllegalArgumentException("A section needs at least one block state");
        }
        if (distinctStates == 1) {
            return SINGLE;
        }
        if (distinctStates <= MAX_INDIRECT_STATES) {
            return INDIRECT;
        }
        return DIRECT;
    }

    static int indirectBits(int distinctStates) {
        if (distinctStates < 2 || distinctStates > MAX_INDIRECT_STATES) {
            throw new IllegalArgumentException("Indirect palettes hold 2 to 256 states, not " + distinctStates);
        }
        return Math.max(MIN_INDIRECT_BITS, ceilLog2(distinctStates));
    }

    static int directBits(int maxStateId) {
        if (maxStateId < 0) {
            throw new IllegalArgumentException("The highest block state ID cannot be negative: " + maxStateId);
        }
        return ceilLog2(maxStateId + 1);
    }

    private static int ceilLog2(int value) {
        return Integer.SIZE - Integer.numberOfLeadingZeros(value - 1);
    }
}
