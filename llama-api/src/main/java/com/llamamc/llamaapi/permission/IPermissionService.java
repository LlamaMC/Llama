package com.llamamc.llamaapi.permission;

import java.util.Set;
import java.util.UUID;

public interface IPermissionService {

    /**
     * Registers a permission node with the value it has for users who are not granted it. Registering the same node
     * again with the same default does nothing.
     *
     * @throws IllegalArgumentException if the node is blank
     * @throws IllegalStateException if the node is already registered with a different default
     */
    void register(String node, PermissionDefault defaultValue);

    /**
     * Returns whether the user holds the node. An explicit grant for the user, or a grant through one of the user's
     * groups, wins. Otherwise the node's default decides: TRUE grants, FALSE denies, and OP grants only to users who
     * hold the node {@code llama.op}.
     */
    boolean hasPermission(UUID user, String node);

    /** Returns an immutable snapshot of every registered node. */
    Set<String> nodes();

}
