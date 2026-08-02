package sh.vork.reflection;

import java.util.Map;

import sh.vork.orm.DatabaseEntity;

/**
 * Runtime connection values for a reflection group.
 *
 * <p>Secret values are not stored here; only non-secret parameter values and base URL override.
 */
public record ReflectionBinding(
        String uuid,
        String groupUuid,
        String name,
        String baseUrl,
        Map<String, String> parameterValues,
        long version,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public ReflectionBinding {
        if (groupUuid == null) {
            groupUuid = "";
        }
        if (name == null || name.isBlank()) {
            name = "default";
        }
        if (baseUrl == null) {
            baseUrl = "";
        }
        if (parameterValues == null) {
            parameterValues = Map.of();
        }
        if (version < 1) {
            version = 1;
        }
    }
}
