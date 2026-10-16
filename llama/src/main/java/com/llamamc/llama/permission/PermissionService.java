package com.llamamc.llama.permission;

import com.llamamc.llama.config.PermissionsConfig;
import com.llamamc.llamaapi.permission.IPermissionService;
import com.llamamc.llamaapi.permission.PermissionDefault;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Reads go to an immutable snapshot of the config and to a concurrent map of defaults, so no lock is needed.
public final class PermissionService implements IPermissionService {
    private static final String OPERATOR_NODE = "llama.op";
    private static final String PERMISSIONS_KEY = "permissions";
    private static final String GROUPS_KEY = "groups";
    private static final Grants NONE = new Grants(Set.of(), Set.of());

    private final Snapshot snapshot;
    private final Map<String, PermissionDefault> defaults = new ConcurrentHashMap<>();

    public PermissionService(PermissionsConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Permissions config must not be null");
        }
        this.snapshot = snapshot(config);
    }

    @Override
    public void register(String node, PermissionDefault defaultValue) {
        if (node == null || node.isBlank()) {
            throw new IllegalArgumentException("Permission node must not be blank");
        }
        if (defaultValue == null) {
            throw new IllegalArgumentException("Permission default must not be null");
        }
        var existing = defaults.putIfAbsent(node, defaultValue);
        if (existing != null && existing != defaultValue) {
            throw new IllegalStateException(
                    "Permission node '%s' is already registered with default %s".formatted(node, existing));
        }
    }

    @Override
    public boolean hasPermission(UUID user, String node) {
        if (user == null) {
            throw new IllegalArgumentException("User must not be null");
        }
        if (node == null) {
            throw new IllegalArgumentException("Permission node must not be null");
        }
        if (grantedByConfig(user, node)) {
            return true;
        }
        return switch (defaults.getOrDefault(node, PermissionDefault.FALSE)) {
            case TRUE -> true;
            case FALSE -> false;
            case OP -> grantedByConfig(user, OPERATOR_NODE);
        };
    }

    @Override
    public Set<String> nodes() {
        return Set.copyOf(defaults.keySet());
    }

    private boolean grantedByConfig(UUID user, String node) {
        var grants = snapshot.users().getOrDefault(user, NONE);
        if (grants.permissions().contains(node)) {
            return true;
        }
        return grants.groups().stream()
                .anyMatch(group -> snapshot.groups().getOrDefault(group, Set.of()).contains(node));
    }

    private static Snapshot snapshot(PermissionsConfig config) {
        return new Snapshot(users(config.getUsers()), groups(config.getGroups()));
    }

    private static Map<UUID, Grants> users(Map<String, Map<String, List<String>>> source) {
        if (source == null) {
            return Map.of();
        }
        var users = new HashMap<UUID, Grants>();
        for (var entry : source.entrySet()) {
            var fields = entry.getValue();
            var permissions = fields == null ? null : fields.get(PERMISSIONS_KEY);
            var groups = fields == null ? null : fields.get(GROUPS_KEY);
            users.put(uuid(entry.getKey()), new Grants(names(permissions), names(groups)));
        }
        return Map.copyOf(users);
    }

    private static Map<String, Set<String>> groups(Map<String, List<String>> source) {
        if (source == null) {
            return Map.of();
        }
        var groups = new HashMap<String, Set<String>>();
        for (var entry : source.entrySet()) {
            groups.put(entry.getKey(), names(entry.getValue()));
        }
        return Map.copyOf(groups);
    }

    private static Set<String> names(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        var names = new HashSet<String>();
        for (var value : values) {
            if (value == null) {
                continue;
            }
            names.add(value);
        }
        return Set.copyOf(names);
    }

    private static UUID uuid(String text) {
        if (text == null) {
            throw new IllegalStateException("Invalid permissions config: a user has no UUID");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Invalid permissions config: '" + text + "' is not a user UUID", error);
        }
    }

    private record Grants(Set<String> permissions, Set<String> groups) {
    }

    private record Snapshot(Map<UUID, Grants> users, Map<String, Set<String>> groups) {
    }
}
