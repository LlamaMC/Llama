package com.llamamc.llamaapi.world;

import java.util.Collection;
import java.util.Optional;

public interface IWorldManager {
    Collection<IWorld> worlds();
    Optional<IWorld> world(String name);
    IWorld mainWorld();
}
