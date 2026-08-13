package sh.vork.github.contribution;

import java.util.List;

/**
 * Auth-independent contribution submission request.
 */
public record ContributionSubmitRequest(
        String localUsername,
        String branchName,
        String commitMessage,
        String prTitle,
        String prBody,
        ContributionTarget target,
        List<ContributionFile> files
) {
}
