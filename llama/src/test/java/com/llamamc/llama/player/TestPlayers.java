package com.llamamc.llama.player;

import com.llamamc.llamaapi.component.Component;
import com.llamamc.llamaapi.concurrent.LlamaFuture;
import com.llamamc.llamaapi.concurrent.LlamaPromise;
import com.llamamc.llamaapi.network.protocol.IPacket;
import com.llamamc.llamaapi.permission.IPermissionService;
import com.llamamc.llamaapi.permission.PermissionDefault;
import com.llamamc.llamaapi.scheduler.IScheduler;
import com.llamamc.llamaapi.scheduler.ITaskHandle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** Test doubles for the player runtime. Each double is used from one thread at a time. */
public final class TestPlayers {

    private TestPlayers() {
    }

    public static final class Connection implements IPlayerConnection {
        private final List<IPacket> sent = new ArrayList<>();
        private Component disconnectReason;
        private Component denyReason;
        private boolean joined;

        public List<IPacket> sent() {
            return List.copyOf(sent);
        }

        public Component disconnectReason() {
            return disconnectReason;
        }

        public Component denyReason() {
            return denyReason;
        }

        public boolean joined() {
            return joined;
        }

        @Override
        public void send(IPacket packet) {
            sent.add(packet);
        }

        @Override
        public void disconnect(Component reason) {
            disconnectReason = reason;
        }

        @Override
        public void denyJoin(Component reason) {
            denyReason = reason;
        }

        @Override
        public void join(List<IPacket> packets, Runnable whenSent) {
            joined = true;
            whenSent.run();
        }
    }

    /** Queues tick tasks until the test runs them, so the order is fixed and no thread is involved. */
    public static final class Scheduler implements IScheduler {
        private final Queue<Runnable> queued = new ArrayDeque<>();

        @Override
        public boolean isTickThread() {
            return true;
        }

        @Override
        public LlamaFuture<Void> runOnTick(Runnable task) {
            return callOnTick(() -> {
                task.run();
                return null;
            });
        }

        @Override
        public <T> LlamaFuture<T> callOnTick(Callable<T> task) {
            var promise = new LlamaPromise<T>();
            queued.add(() -> {
                try {
                    promise.complete(task.call());
                } catch (Exception error) {
                    promise.fail(error);
                }
            });
            return promise.future();
        }

        @Override
        public ITaskHandle runLaterTicks(int ticks, Runnable task) {
            throw new UnsupportedOperationException("Not used by the player runtime tests");
        }

        @Override
        public ITaskHandle runRepeating(int periodTicks, Runnable task) {
            throw new UnsupportedOperationException("Not used by the player runtime tests");
        }

        @Override
        public <T> LlamaFuture<T> supplyAsync(Supplier<T> task) {
            throw new UnsupportedOperationException("Not used by the player runtime tests");
        }

        /** Runs the queued tasks in order. Tasks queued by those tasks run in the same call. */
        public void runQueued() {
            while (!queued.isEmpty()) {
                queued.poll().run();
            }
        }
    }

    public static final class Permissions implements IPermissionService {
        private final Map<UUID, Set<String>> grants = new HashMap<>();

        public void grant(UUID user, String node) {
            grants.computeIfAbsent(user, key -> new HashSet<>()).add(node);
        }

        @Override
        public void register(String node, PermissionDefault defaultValue) {
        }

        @Override
        public boolean hasPermission(UUID user, String node) {
            return grants.getOrDefault(user, Set.of()).contains(node);
        }

        @Override
        public Set<String> nodes() {
            return Set.of();
        }
    }
}
