package com.llamamc.llamaapi.world;

public interface IWorld {

    String name();
    
    default DimensionType dimension() {
        return DimensionType.OVERWORLD;
    }

    default WorldType type() {
        return WorldType.NORMAL;
    }
}
