package sh.vork.ai.function;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import sh.vork.skill.SkillParameter;
import sh.vork.skill.SkillSecret;

/**
 * Input schema for the {@code createSkill} tool.
 *
 * <p>This tool is intentionally dumb: it persists the provided skill fields
 * without inferring design decisions from natural language.</p>
 */
public record CreateSkillRequest(
        @JsonProperty(required = true, value = "name")
        @JsonPropertyDescription("Skill display name.")
        String name,

        @JsonProperty(required = true, value = "description")
        @JsonPropertyDescription("Human-readable skill description.")
        String description,

        @JsonProperty(required = true, value = "groupUuid")
        @JsonPropertyDescription("Target skill group UUID.")
        String groupUuid,

        @JsonProperty(value = "autoShareWithinGroup")
        @JsonPropertyDescription("Whether this skill should be auto-shared within its group.")
        Boolean autoShareWithinGroup,

        @JsonProperty(value = "parameters")
        @JsonPropertyDescription("Runtime input parameters required by this skill.")
        List<SkillParameter> parameters,

        @JsonProperty(required = true, value = "instructions")
        @JsonPropertyDescription("Deterministic execution instructions for the skill runtime.")
        String instructions,

        @JsonProperty(value = "allowedTools")
        @JsonPropertyDescription("Tool IDs the skill is allowed to call.")
        List<String> allowedTools,

        @JsonProperty(value = "allowedTypes")
        @JsonPropertyDescription("Optional runtime type FQNs the skill may access.")
        List<String> allowedTypes,

        @JsonProperty(value = "subSkillUuids")
        @JsonPropertyDescription("Optional UUIDs of dependent sub-skills.")
        List<String> subSkillUuids,

        @JsonProperty(value = "secrets")
        @JsonPropertyDescription("Optional skill secret declarations.")
        List<SkillSecret> secrets
) {
    public boolean autoShareEffective() {
        return Boolean.TRUE.equals(autoShareWithinGroup);
    }
}
