package sh.vork.scheduling.domain;

import sh.vork.artifact.ArtifactStatus;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import sh.vork.orm.DatabaseEntity;

/**
 * Persistent scheduled background AI job definition.
 *
 * <p>Jobs are owned by a user ({@code userId}) and can be triggered manually,
 * run once at a scheduled time, or repeat on a fixed interval.  An optional
 * agent template, provider, and model can be pinned to override the defaults
 * used when the job executes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScheduledJob(
        String id,
        String name,
        String aiPrompt,
        String sessionUuid,
        String userId,                 // user primary key (VorkUser.uuid == username)
        InvocationType invocationType, // MANUAL | ONE_TIME | REPEAT
        Instant startTime,
        long repeatDuration,
        DurationType durationType,
        long lastExecutionTime,        // epoch ms of last run start, 0 = never
        long nextExecutionTime,        // epoch ms of next scheduled run, 0 = N/A (MANUAL or completed ONE_TIME)
        String agentTemplateId,        // optional — pinned agent template UUID
        String provider,               // optional — AiProvider name override
        String modelId,                // optional — model ID override
        int oobTimeoutMinutes,         // minutes before the OOB relay auth link expires; 0 = use system default
        String expectedOutput,         // optional — describes the required output/result; enforced via protocol
        ScheduledJobStatus status,
        List<String> skillUuids,       // optional — extra skill UUIDs available for the duration of this job's session
        List<String> toolIds,          // optional — extra tool bean IDs available for the duration of this job's session
        List<String> notificationUserIds, // local state: users to notify for paused-input events
        String groupId,
        String artifactId,
        String version,
        ArtifactStatus artifactStatus
) implements DatabaseEntity {

    private static final String DEFAULT_VERSION = "SNAPSHOT";

    public ScheduledJob {
        groupId = normalizeIdentifier(groupId);
        artifactId = normalizeIdentifier(artifactId);
        version = normalizeVersion(version);
        artifactStatus = artifactStatus == null ? ArtifactStatus.SNAPSHOT : artifactStatus;
        notificationUserIds = normalizeUsernames(notificationUserIds);
    }

    public ScheduledJob(String id,
                        String name,
                        String aiPrompt,
                        String sessionUuid,
                        String userId,
                        InvocationType invocationType,
                        Instant startTime,
                        long repeatDuration,
                        DurationType durationType,
                        long lastExecutionTime,
                        long nextExecutionTime,
                        String agentTemplateId,
                        String provider,
                        String modelId,
                        int oobTimeoutMinutes,
                        String expectedOutput,
                        ScheduledJobStatus status,
                        List<String> skillUuids,
                        List<String> toolIds) {
        this(id, name, aiPrompt, sessionUuid, userId, invocationType, startTime, repeatDuration,
                durationType, lastExecutionTime, nextExecutionTime, agentTemplateId, provider, modelId,
                oobTimeoutMinutes, expectedOutput, status, skillUuids, toolIds,
                    List.of(),
                null, null, null, ArtifactStatus.SNAPSHOT);
    }

    @Override
    public String uuid() {
        return id;
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

    private static List<String> normalizeUsernames(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }
}

