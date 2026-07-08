package sh.vork.skill;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A typed input parameter declared on a {@link Skill}.
 *
 * <p>When an agent calls {@code executeSkill}, it must supply a value for every
 * declared parameter.  The {@code type} is a hint for both the calling agent
 * (so it knows what format to pass) and for the skill's initial prompt
 * (so the AI knows how to interpret each value).
 *
 * <p>Valid {@code type} values: {@code string}, {@code text}, {@code int},
 * {@code double}, {@code boolean}, {@code secret}. The {@code text} type is
 * treated as a string value but rendered as a multi-line user input prompt.
 * Secret values are masked in logs and not echoed back in tool responses.
 *
 * @param name        parameter identifier (used as the map key in the tool call)
 * @param type        one of: string | text | int | double | boolean | secret
 * @param description optional human-readable hint passed into the skill prompt
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SkillParameter(
        String name,
        String type,
        String description,
    SkillParameterInputMode inputMode
) {
    public SkillParameter {
        if (name == null || name.isBlank()) name = "param";
        if (type == null || type.isBlank()) type = "string";
        if (description == null)            description = "";
    if (inputMode == null)              inputMode = SkillParameterInputMode.AI_REQUIRED;
    }

    public SkillParameter(String name,
                          String type,
                          String description) {
        this(name, type, description, SkillParameterInputMode.AI_REQUIRED);
    }

    /** Returns {@code true} when this parameter's value should be masked in logs. */
    @JsonIgnore
    public boolean isSecret() {
        return "secret".equalsIgnoreCase(type);
    }
}
