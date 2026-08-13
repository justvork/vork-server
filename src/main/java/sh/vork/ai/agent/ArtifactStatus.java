package sh.vork.ai.agent;

/**
 * Lifecycle state for user-managed artifacts.
 */
public enum ArtifactStatus {
    SNAPSHOT,
    SUBMITTED,
    REJECTED,
    STAGED,
    PUBLISHED
}
