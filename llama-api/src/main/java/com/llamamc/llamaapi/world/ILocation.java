package com.llamamc.llamaapi.world;

public interface ILocation {
    double x();
    double y();
    double z();
    float yaw();
    float pitch();
    IWorld world();
}
