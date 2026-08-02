package sh.vork.reflection;

import sh.vork.orm.DatabaseEntity;

/**
 * Group metadata for related reflections.
 */
public record ReflectionGroup(
        String uuid,
        String name,
        String description,
        ReflectionType type,
        long version,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public ReflectionGroup {
        if (name == null || name.isBlank()) {
            name = "Unnamed Group";
        }
        if (description == null) {
            description = "";
        }
        if (type == null) {
            type = ReflectionType.REST;
        }
        if (version < 1) {
            version = 1;
        }
    }
}
