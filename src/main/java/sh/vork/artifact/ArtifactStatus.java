package sh.vork.artifact;

/**
 * Shared lifecycle state for all GitHub-contributed artifacts.
 */
public enum ArtifactStatus {
    SNAPSHOT,
    SUBMITTED,
    REJECTED,
    STAGED,
    PUBLISHED
}
