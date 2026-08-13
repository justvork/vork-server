package sh.vork.github.contribution;

import sh.vork.orm.DatabaseEntity;

/**
 * Tracks the upstream pull request associated with a submitted artifact version.
 */
public record ContributionSubmission(
        String uuid,
        String artifactType,
        String artifactUuid,
        String upstreamOwner,
        String upstreamRepository,
        String baseBranch,
        String branchName,
        long pullRequestNumber,
        String pullRequestUrl,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {
}
