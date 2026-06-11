package sh.vork.skill;

import sh.vork.orm.DatabaseEntity;

import java.util.List;

/**
 * A sandboxed AI execution unit that combines a system prompt, a restricted
 * tool set, typed input parameters, and optional Java record types into a
 * reusable skill.
 *
 * <p>When executed, a skill runs in its own {@link sh.vork.ai.entity.AiSession}
 * with {@code originMode=SKILL} so that authorization/input requests surface
 * through the normal pending-sessions flow and the session is fully resumable.
 *
 * @param uuid           unique MongoDB _id
 * @param name           human-readable name
 * @param author         author / maintainer of this skill
 * @param description    brief description of what this skill does
 * @param category       category from the vork-skills canonical list
 * @param parameters     typed input parameters the caller must supply
 * @param outputTemplate free-text template showing what the skill is expected to
 *                       return (may be JSON, plain text, or any example)
 * @param instructions   system prompt injected into the skill's AI session
 * @param allowedTools   Spring bean IDs of tools available inside the skill
 *                       (empty = all tools available)
 * @param allowedTypes   FQNs of Java record types the skill may operate on
 * @param version        incremented on every update
 * @param createdAt      epoch milliseconds
 * @param updatedAt      epoch milliseconds of last modification
 */
public record Skill(
        String                uuid,
        String                name,
        String                author,
        String                description,
        String                category,
        List<SkillParameter>  parameters,
        String                outputTemplate,
        String                instructions,
        List<String>          allowedTools,
        List<String>          allowedTypes,
        long                  version,
        long                  createdAt,
        long                  updatedAt
) implements DatabaseEntity {

    public Skill {
        if (name == null || name.isBlank())  name = "Unnamed Skill";
        if (author == null)                  author = "";
        if (description == null)             description = "";
        if (category == null)                category = "";
        if (parameters == null)              parameters = List.of();
        if (outputTemplate == null)          outputTemplate = "";
        if (instructions == null)            instructions = "";
        if (allowedTools == null)            allowedTools = List.of();
        if (allowedTypes == null)            allowedTypes = List.of();
        if (version < 1)                     version = 1;
    }
}
