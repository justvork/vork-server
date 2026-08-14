package sh.vork.oauth;

/**
 * Lifecycle state for OAuth template contributions.
 */
public enum ArtifactStatus {
    SNAPSHOT,
    SUBMITTED,
    REJECTED,
    STAGED,
    PUBLISHED
}