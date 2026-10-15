package com.llamamc.llamaapi.block;

import com.llamamc.llamaapi.world.BlockState;
import com.llamamc.llamaapi.world.IWorld;

public interface IBlock {
    IWorld world();
    int x();
    int y();
    int z();
    BlockState state();
    void state(BlockState state);
}
