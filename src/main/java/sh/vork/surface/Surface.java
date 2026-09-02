package sh.vork.surface;

import sh.vork.artifact.ArtifactStatus;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonIgnoreProperties(ignoreUnknown = true)
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
    boolean published,
    String logoDataUrl,
    List<String> assignedUserUuids,
    AccessPolicy accessPolicy,
        String groupId,
        String artifactId,
        String version,
        ArtifactStatus artifactStatus,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    private static final String DEFAULT_VERSION = "SNAPSHOT";

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
        if (logoDataUrl == null) {
            logoDataUrl = "";
        }
        if (assignedUserUuids == null) {
            assignedUserUuids = List.of();
        }
        if (accessPolicy == null) {
            accessPolicy = AccessPolicy.defaultPolicy();
        }

        groupId = normalizeIdentifier(groupId);
        artifactId = normalizeIdentifier(artifactId);
        version = normalizeVersion(version);
        artifactStatus = artifactStatus == null ? ArtifactStatus.SNAPSHOT : artifactStatus;
    }

    public Surface(String uuid,
                   String toolId,
                   String name,
                   String description,
                   String sessionUuid,
                   String executionSessionUuid,
                   List<String> skillUuids,
                   List<String> reflectionBindingUuids,
                   List<String> jobUuids,
                   long createdAt,
                   long updatedAt) {
        this(uuid, toolId, name, description, sessionUuid, executionSessionUuid,
                skillUuids, reflectionBindingUuids, jobUuids,
                false, "", List.of(), AccessPolicy.defaultPolicy(),
                null, null, null, ArtifactStatus.SNAPSHOT,
                createdAt, updatedAt);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccessPolicy(
            boolean homeScreenEnabled,
            boolean navButtonEnabled,
            String navButtonIcon,
            boolean privateUrlEnabled,
            String privateUrlPath,
            boolean publicUrlEnabled,
            String publicUrlPath
    ) {

        public AccessPolicy {
            if (navButtonIcon == null) {
                navButtonIcon = "";
            }
            if (privateUrlPath == null) {
                privateUrlPath = "";
            }
            if (publicUrlPath == null) {
                publicUrlPath = "";
            }
        }

        public static AccessPolicy defaultPolicy() {
            return new AccessPolicy(false, false, "", false, "", false, "");
        }
    }

    private static String normalizeIdentifier(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.isBlank()) {
            return null;
        }
        return normalized;
    }

    private static String normalizeVersion(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_VERSION;
        }
        return raw.trim();
    }

    @JsonIgnore
    public boolean isSnapshotMutable() {
        return artifactStatus == null || artifactStatus == ArtifactStatus.SNAPSHOT;
    }

    @JsonIgnore
    public boolean isDeletable() {
        return artifactStatus == null
                || artifactStatus == ArtifactStatus.SNAPSHOT
                || artifactStatus == ArtifactStatus.SUBMITTED
                || artifactStatus == ArtifactStatus.REJECTED;
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
