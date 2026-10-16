package com.llamamc.llama.world;

import com.llamamc.llamaapi.world.BlockState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockStateStringsTest {
    @Test
    void formatsTheKeyAndSortedProperties() {
        var slab = new BlockState("minecraft:oak_slab", Map.of("waterlogged", "false", "type", "top"));

        assertEquals("minecraft:oak_slab[type=top,waterlogged=false]", BlockStateStrings.format(slab));
        assertEquals("minecraft:air", BlockStateStrings.format(BlockState.AIR));
    }

    @Test
    void parsesTheFormatBackToTheSameState() {
        var states = new BlockState[] {
                new BlockState("minecraft:oak_slab", Map.of("waterlogged", "false", "type", "top")),
                new BlockState("minecraft:lever", Map.of("face", "floor", "facing", "north", "powered", "true")),
                new BlockState("minecraft:stone"),
        };

        for (var state : states) {
            assertEquals(state, BlockStateStrings.parse(BlockStateStrings.format(state)));
        }
    }

    @Test
    void parsesPropertiesInAnyOrder() {
        assertEquals(new BlockState("minecraft:oak_slab", Map.of("type", "top", "waterlogged", "false")),
                BlockStateStrings.parse("minecraft:oak_slab[waterlogged=false,type=top]"));
    }

    @Test
    void rejectsMalformedStateStrings() {
        assertThrows(IllegalArgumentException.class, () -> BlockStateStrings.parse("minecraft:oak_slab[type=top"));
        assertThrows(IllegalArgumentException.class, () -> BlockStateStrings.parse("minecraft:stone[=x]"));
        assertThrows(IllegalArgumentException.class, () -> BlockStateStrings.parse("minecraft:stone[type=]"));
        assertThrows(IllegalArgumentException.class, () -> BlockStateStrings.parse("minecraft:stone[a=1,a=2]"));
        assertThrows(IllegalArgumentException.class, () -> BlockStateStrings.parse("[a=1]"));
    }

    @Test
    void refusesToFormatAValueThatWouldBeAmbiguous() {
        var state = new BlockState("minecraft:stone", Map.of("name", "a,b"));

        assertThrows(IllegalArgumentException.class, () -> BlockStateStrings.format(state));
    }
}
