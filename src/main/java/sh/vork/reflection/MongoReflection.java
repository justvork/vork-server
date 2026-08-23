package sh.vork.reflection;

import java.util.List;

import sh.vork.orm.DatabaseEntity;

/**
 * MONGO reflection definition stored independently from URL-based reflections.
 */
public record MongoReflection(
        String uuid,
        String id,
        String name,
        String description,
        String groupUuid,
        List<ReflectionInputParameter> inputParameters,
        String outputSchema,
        long version,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public MongoReflection {
        if (id == null) {
            id = "";
        }
        if (name == null || name.isBlank()) {
            name = "Unnamed Reflection";
        }
        if (description == null) {
            description = "";
        }
        if (groupUuid == null) {
            groupUuid = "";
        }
        if (inputParameters == null) {
            inputParameters = List.of();
        }
        if (outputSchema == null) {
            outputSchema = "";
        }
        if (version < 1) {
            version = 1;
        }
    }
}
