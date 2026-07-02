package sh.vork.skill;

import sh.vork.orm.DatabaseEntity;
import sh.vork.typegen.ExportableType;

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
 * @param description    brief description of what this skill does
 * @param groupUuid      parent group UUID that owns this skill
 * @param autoShareWithinGroup if true, this skill is implicitly available as a
 *                       sub-skill to other skills in the same group
 * @param parameters     typed input parameters the caller must supply
 * @param instructions   system prompt injected into the skill's AI session
 * @param allowedTools   Spring bean IDs of tools available inside the skill
 *                       (empty = all tools available)
 * @param allowedTypes   FQNs of Java record types the skill may operate on
 * @param subSkillUuids  UUIDs of sub-skills this skill may invoke; these are
 *                       injected as tools at runtime alongside {@code allowedTools}
 * @param version        incremented on every update
 * @param createdAt      epoch milliseconds
 * @param updatedAt      epoch milliseconds of last modification
 */
public record Skill(
        String                uuid,
        String                name,
        String                description,
    String                groupUuid,
    boolean               autoShareWithinGroup,
        List<SkillParameter>  parameters,
        String                instructions,
        List<String>          allowedTools,
        List<String>          allowedTypes,
        List<String>          subSkillUuids,
        long                  version,
        long                  createdAt,
        long                  updatedAt,
        List<SkillSecret>     secrets
) implements DatabaseEntity {

    public Skill {
        if (name == null || name.isBlank())  name = "Unnamed Skill";
        if (description == null)             description = "";
        if (groupUuid == null)               groupUuid = "";
        if (parameters == null)              parameters = List.of();
        if (instructions == null)            instructions = "";
        if (allowedTools == null)            allowedTools = List.of();
        if (allowedTypes == null)            allowedTypes = List.of();
        if (subSkillUuids == null)           subSkillUuids = List.of();
        if (version < 1)                     version = 1;
        if (secrets == null)                 secrets = List.of();
    }

    /**
     * Derives a stable camelCase tool name from the skill's human-readable name.
     * Non-alphanumeric characters are stripped; words are joined in camelCase.
     * <p>Examples: "SSH Security Scan" → {@code "sshSecurityScan"},
     *              "Run Port Scan"     → {@code "runPortScan"}.
     * If the result would start with a digit it is prefixed with {@code "skill_"}.
     */
    public String toolName() {
        String[] words = name.replaceAll("[^a-zA-Z0-9\\s]", " ").trim().split("\\s+");
        if (words.length == 0 || (words.length == 1 && words[0].isBlank())) {
            return "skill_" + uuid.replace("-", "").substring(0, 8);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isBlank()) continue;
            if (sb.isEmpty()) {
                sb.append(Character.toLowerCase(w.charAt(0))).append(w.substring(1).toLowerCase());
            } else {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
            }
        }
        String result = sb.toString();
        if (result.isEmpty()) return "skill_" + uuid.replace("-", "").substring(0, 8);
        return Character.isDigit(result.charAt(0)) ? "skill_" + result : result;
    }
}
