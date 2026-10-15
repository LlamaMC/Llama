package com.llamamc.llamaapi.world;

import java.nio.file.Path;

public interface IWorld {

    String name();
    String seed();
    Path directory();
    IChunk chunk(int chunkX, int chunkZ);

    default DimensionType dimension() {
        return DimensionType.OVERWORLD;
    }

    default WorldType type() {
        return WorldType.NORMAL;
    }

}
