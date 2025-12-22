package com.llamamc.llamaapi.world;

import java.nio.file.Path;

public interface IWorld {

    String name();
    String seed();
    Path directory();
    default DimensionType dimension() {
        return DimensionType.OVERWORLD;
    }
    default WorldType type() {
        return WorldType.NORMAL;
    }

}
