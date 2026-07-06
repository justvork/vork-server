package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import sh.vork.skill.SkillVisibility;

/**
 * Input schema for the {@code designSkillFromRequest} tool.
 *
 * <p>This tool performs design only and never persists skills.</p>
 */
public record DesignSkillRequest(
        @JsonProperty(required = true, value = "request")
        @JsonPropertyDescription("Natural-language description of the skill to design.")
        String request,

        @JsonProperty(value = "skillName")
        @JsonPropertyDescription("Optional explicit skill name. If omitted, one is generated.")
        String skillName,

        @JsonProperty(value = "category")
        @JsonPropertyDescription("Optional category hint for group resolution.")
        String category,

        @JsonProperty(value = "targetGroup")
        @JsonPropertyDescription("Optional target skill-group name or UUID for placement.")
        String targetGroup,

        @JsonProperty(value = "author")
        @JsonPropertyDescription("Optional author label override.")
        String author,

        @JsonProperty(value = "visibility")
        @JsonPropertyDescription("Optional visibility override for generated skill request (PUBLIC or PRIVATE).")
        SkillVisibility visibility,

        @JsonProperty(value = "dryRun")
        @JsonPropertyDescription("Accepted for compatibility; designSkillFromRequest is always dry-run behavior.")
        Boolean dryRun
) {}
