package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code exportJavaType} function tool.
 */
public record ExportJavaTypeRequest(
        @JsonProperty(required = true, value = "fqn")
        @JsonPropertyDescription("Fully-qualified class name of the type to export, e.g. sh.vork.skill.Skill")
        String fqn,

        @JsonProperty(value = "mode")
        @JsonPropertyDescription("Data export mode: BY_ID (default) or ALL")
        String mode,

        @JsonProperty(value = "uuid")
        @JsonPropertyDescription("Required when mode=BY_ID. Export exactly one entity instance by UUID.")
        String uuid
) {}
