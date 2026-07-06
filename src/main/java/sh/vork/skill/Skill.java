package sh.vork.skill;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
 * @param visibility     PUBLIC skills are visible to end users; PRIVATE skills
 *                       are only callable from skills inside the same group
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
@JsonIgnoreProperties(ignoreUnknown = true)
@ExportableType(description = "Skill definition")
public record Skill(
        String                uuid,
        String                name,
        String                description,
    String                groupUuid,
    SkillVisibility       visibility,
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
        if (visibility == null)              visibility = SkillVisibility.PUBLIC;
        if (parameters == null)              parameters = List.of();
        if (instructions == null)            instructions = "";
        if (allowedTools == null)            allowedTools = List.of();
        if (allowedTypes == null)            allowedTypes = List.of();
        if (subSkillUuids == null)           subSkillUuids = List.of();
        if (version < 1)                     version = 1;
        if (secrets == null)                 secrets = List.of();
    }

    /**
     * Derives a stable machine tool name from the skill group + skill name.
     *
     * <p>The format is provider-safe (letters, numbers, underscore only):
     * {@code <groupSegment>_<skillSegment>} where each segment is camelCase.
     * This avoids collisions when different groups have skills with the same
     * human-friendly name (for example multiple "Connect" skills).
     *
     * <p>Examples:
     * <ul>
     *   <li>groupUuid "googleCalendarViewer", name "Connect" → {@code "googleCalendarViewer_connect"}</li>
     *   <li>groupUuid "grp-mail", name "Connect" → {@code "grpMail_connect"}</li>
     * </ul>
     *
     * <p>If the resulting identifier would start with a digit it is prefixed
     * with {@code "skill_"}. Tool names are capped to 64 chars.
     */
    public String toolName() {
        String groupPart = toCamelIdentifier(groupUuid);
        String skillPart = toCamelIdentifier(name);

        String result;
        if (groupPart.isBlank() && skillPart.isBlank()) {
            result = "skill_" + uuid.replace("-", "").substring(0, 8);
        } else if (groupPart.isBlank()) {
            result = skillPart;
        } else if (skillPart.isBlank()) {
            result = groupPart;
        } else {
            result = groupPart + "_" + skillPart;
        }

        if (result.length() > 64) {
            result = result.substring(0, 55) + "_" + uuid.replace("-", "").substring(0, 8);
        }
        if (result.isEmpty()) {
            result = "skill_" + uuid.replace("-", "").substring(0, 8);
        }
        return Character.isDigit(result.charAt(0)) ? "skill_" + result : result;
    }

    private static String toCamelIdentifier(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String[] words = raw.replaceAll("[^a-zA-Z0-9\\s]", " ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w == null || w.isBlank()) continue;
            if (sb.isEmpty()) {
                sb.append(Character.toLowerCase(w.charAt(0))).append(w.substring(1));
            } else {
                sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }
}
