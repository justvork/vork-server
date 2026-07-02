package sh.vork.security;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Central role-to-permission mapping.
 */
public final class RolePermissionPolicy {

    private static final Map<UserRole, Set<Permission>> ROLE_PERMISSIONS = new EnumMap<>(UserRole.class);
        private static final Map<String, Permission> TOOL_PERMISSIONS = Map.of(
            "createSkill", Permission.SKILLS_WRITE,
            "compileJavaType", Permission.TYPES_WRITE
        );

    static {
        ROLE_PERMISSIONS.put(UserRole.ADMIN, Set.of(Permission.values()));
        ROLE_PERMISSIONS.put(UserRole.USER, Set.of());
    }

    private RolePermissionPolicy() {
    }

    public static Set<Permission> permissionsFor(UserRole role) {
        return ROLE_PERMISSIONS.getOrDefault(role, Set.of());
    }

    public static boolean hasPermission(UserRole role, Permission permission) {
        return permissionsFor(role).contains(permission);
    }

    public static Set<String> authoritiesFor(UserRole role) {
        return Stream.concat(
                        Stream.of(role.authority()),
                        permissionsFor(role).stream().map(Permission::authority))
                .collect(Collectors.toUnmodifiableSet());
    }

    public static Optional<Permission> requiredPermissionForTool(String toolName) {
        return Optional.ofNullable(TOOL_PERMISSIONS.get(toolName));
    }
}
