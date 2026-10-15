package com.llamamc.llamaapi.world.migration;

public interface IWorldMigration {
    MigrationStatus status();
    void start();
    void cancel();
}
