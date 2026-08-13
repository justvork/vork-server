package sh.vork.github.contribution;

/**
 * Canonical contribution file payload written into a repository branch.
 */
public record ContributionFile(
        String path,
        String content
) {
}
