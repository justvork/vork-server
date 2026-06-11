package sh.vork.skill;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A typed input parameter declared on a {@link Skill}.
 *
 * <p>When an agent calls {@code executeSkill}, it must supply a value for every
 * declared parameter.  The {@code type} is a hint for both the calling agent
 * (so it knows what format to pass) and for the skill's initial prompt
 * (so the AI knows how to interpret each value).
 *
 * <p>Valid {@code type} values: {@code string}, {@code int}, {@code double},
 * {@code boolean}, {@code secret}.  Secret values are masked in logs and not
 * echoed back in tool responses.
 *
 * @param name        parameter identifier (used as the map key in the tool call)
 * @param type        one of: string | int | double | boolean | secret
 * @param description optional human-readable hint passed into the skill prompt
 */
public record SkillParameter(
        String name,
        String type,
        String description
) {
    public SkillParameter {
        if (name == null || name.isBlank()) name = "param";
        if (type == null || type.isBlank()) type = "string";
        if (description == null)            description = "";
    }

    /** Returns {@code true} when this parameter's value should be masked in logs. */
    @JsonIgnore
    public boolean isSecret() {
        return "secret".equalsIgnoreCase(type);
    }
}
