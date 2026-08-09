package sh.vork.surface;

import sh.vork.orm.DatabaseEntity;

import java.util.List;

/**
 * A user-created Surface — a custom web entry point backed by an AI session and
 * session file artifacts.
 *
 * <p>The Surface record is intentionally a lightweight pointer to an
 * {@link sh.vork.ai.entity.AiSession}.  The session stores the conversation
 * history and file output; this record stores the surface's metadata and
 * references to skills, reflection bindings, and jobs that will be wired into
 * the surface runtime in future milestones.
 */
public record Surface(
        String uuid,
    String toolId,
        String name,
        String description,
        String sessionUuid,
        String executionSessionUuid,
        List<String> skillUuids,
        List<String> reflectionBindingUuids,
        List<String> jobUuids,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public Surface {
        if (name == null || name.isBlank()) {
            name = "Untitled Surface";
        }
        if (toolId == null || toolId.isBlank()) {
            toolId = normalizeToolId(name);
        } else {
            toolId = normalizeToolId(toolId);
        }
        if (description == null) {
            description = "";
        }
        if (sessionUuid == null) {
            sessionUuid = "";
        }
        if (executionSessionUuid == null) {
            executionSessionUuid = "";
        }
        if (skillUuids == null) {
            skillUuids = List.of();
        }
        if (reflectionBindingUuids == null) {
            reflectionBindingUuids = List.of();
        }
        if (jobUuids == null) {
            jobUuids = List.of();
        }
    }

    private static String normalizeToolId(String source) {
        StringBuilder sb = new StringBuilder();
        String raw = source == null ? "" : source.trim().toLowerCase();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        String normalized = sb.toString();
        if (normalized.isBlank()) {
            return "surface";
        }
        if (Character.isDigit(normalized.charAt(0))) {
            return "s" + normalized;
        }
        return normalized;
    }
}
