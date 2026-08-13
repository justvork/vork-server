package sh.vork.github.contribution;

/**
 * Result of branch/file/PR submission workflow.
 */
public record ContributionSubmitResult(
        String upstreamOwner,
        String upstreamRepository,
        String baseBranch,
        String forkOwner,
        String forkRepository,
        String branchName,
        long pullRequestNumber,
        String pullRequestUrl
) {
}
