package sh.vork.reflection;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import sh.vork.orm.DatabaseEntity;
import sh.vork.skill.SkillSecret;

/**
 * Group metadata for related reflections.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReflectionGroup(
        String uuid,
        String name,
        String description,
        ReflectionType type,
    String baseUrl,
    List<SkillSecret> bindingSecrets,
    List<ReflectionBindingParameter> bindingParameters,
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
        if (baseUrl == null) {
            baseUrl = "";
        }
        if (bindingSecrets == null) {
            bindingSecrets = List.of();
        }
        if (bindingParameters == null) {
            bindingParameters = List.of();
        }
        if (version < 1) {
            version = 1;
        }
    }
}
