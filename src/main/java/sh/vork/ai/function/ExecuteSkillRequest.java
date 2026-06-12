package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.Map;

/**
 * Input schema for the {@code executeSkill} tool.
 *
 * <p>The calling agent supplies the skill UUID and a map of parameter
 * name→value for every parameter declared on the {@link sh.vork.skill.Skill}.
 * Secret parameter values are accepted but masked in all logs and responses.
 */
public record ExecuteSkillRequest(

        @JsonProperty(required = true, value = "skillUuid")
        @JsonPropertyDescription("UUID of the Skill to execute")
        String skillUuid,

        @JsonProperty(required = true, value = "parameters")
        @JsonPropertyDescription("Parameter values as a flat object mapping each parameter name to its string value. All declared skill parameters must be supplied as top-level keys — do not nest under a 'parameters' key.")
        Map<String, Object> parameters
) {}
