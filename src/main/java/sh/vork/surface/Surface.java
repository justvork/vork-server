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
        String name,
        String description,
        String sessionUuid,
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
        if (description == null) {
            description = "";
        }
        if (sessionUuid == null) {
            sessionUuid = "";
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
}
