package sh.vork.github.contribution;

/**
 * Official repository target for contribution pull requests.
 */
public record ContributionTarget(
        String owner,
        String repository,
        String baseBranch
) {
}
