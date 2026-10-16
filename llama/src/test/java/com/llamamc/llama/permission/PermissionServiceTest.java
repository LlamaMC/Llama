package com.llamamc.llama.permission;

import com.llamamc.llama.config.PermissionsConfig;
import com.llamamc.llamaapi.permission.PermissionDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class PermissionServiceTest {
    private static final UUID PLAIN = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID GRANTED = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID OPERATOR = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final String OP_NODE = "llama.op";

    @Test
    void trueDefaultGrantsEveryUser() {
        var service = service();
        service.register("test.open", PermissionDefault.TRUE);

        assertTrue(service.hasPermission(PLAIN, "test.open"));
        assertTrue(service.hasPermission(OPERATOR, "test.open"));
    }

    @Test
    void falseDefaultDeniesEveryUserWithoutAGrant() {
        var service = service();
        service.register("test.closed", PermissionDefault.FALSE);

        assertFalse(service.hasPermission(PLAIN, "test.closed"));
        assertFalse(service.hasPermission(OPERATOR, "test.closed"));
    }

    @Test
    void opDefaultGrantsOnlyToUsersWithOperatorNode() {
        var service = service();
        service.register("test.op", PermissionDefault.OP);

        assertFalse(service.hasPermission(PLAIN, "test.op"));
        assertTrue(service.hasPermission(OPERATOR, "test.op"));
    }

    @Test
    void explicitGrantOverridesFalseDefault() {
        var service = service();
        service.register("test.explicit", PermissionDefault.FALSE);

        assertTrue(service.hasPermission(GRANTED, "test.explicit"));
        assertFalse(service.hasPermission(PLAIN, "test.explicit"));
    }

    @Test
    void groupGrantAppliesToMembersOfTheGroup() {
        var service = service();
        service.register("test.group", PermissionDefault.FALSE);

        assertTrue(service.hasPermission(MEMBER, "test.group"));
        assertFalse(service.hasPermission(PLAIN, "test.group"));
    }

    @Test
    void unregisteredNodeIsDeniedUnlessGranted() {
        var service = service();

        assertTrue(service.hasPermission(GRANTED, "test.explicit"));
        assertFalse(service.hasPermission(PLAIN, "test.unknown"));
    }

    @Test
    void registeringSameNodeWithSameDefaultIsAllowed() {
        var service = service();
        service.register("test.same", PermissionDefault.OP);
        service.register("test.same", PermissionDefault.OP);

        assertEquals(1, service.nodes().size());
    }

    @Test
    void registeringSameNodeWithDifferentDefaultThrows() {
        var service = service();
        service.register("test.clash", PermissionDefault.TRUE);

        assertThrows(IllegalStateException.class, () -> service.register("test.clash", PermissionDefault.OP));
        assertTrue(service.hasPermission(PLAIN, "test.clash"));
    }

    @Test
    void registerRejectsBlankNodeAndNullDefault() {
        var service = service();

        assertThrows(IllegalArgumentException.class, () -> service.register(" ", PermissionDefault.TRUE));
        assertThrows(IllegalArgumentException.class, () -> service.register("test.node", null));
        assertTrue(service.nodes().isEmpty());
    }

    @Test
    void nodesReturnsSnapshotOfRegisteredNodes() {
        var service = service();
        service.register("test.one", PermissionDefault.TRUE);
        var snapshot = service.nodes();
        service.register("test.two", PermissionDefault.FALSE);

        assertEquals(List.of("test.one"), List.copyOf(snapshot));
        assertEquals(2, service.nodes().size());
    }

    private static PermissionService service() {
        var config = new PermissionsConfig();
        config.setUsers(Map.of(
                GRANTED.toString(), Map.of("permissions", List.of("test.explicit")),
                MEMBER.toString(), Map.of("groups", List.of("moderators")),
                OPERATOR.toString(), Map.of("permissions", List.of(OP_NODE))));
        config.setGroups(Map.of("moderators", List.of("test.group")));
        return new PermissionService(config);
    }
}
