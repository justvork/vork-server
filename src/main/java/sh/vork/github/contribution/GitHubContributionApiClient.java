package sh.vork.github.contribution;

import java.util.List;

public interface GitHubContributionApiClient {

    String getAuthenticatedLogin(String accessToken);

    ForkRef ensureFork(String accessToken, String upstreamOwner, String upstreamRepository);

    String getDefaultBranch(String accessToken, String owner, String repository);

    String getBranchHeadSha(String accessToken, String owner, String repository, String branch);

    void createBranch(String accessToken,
                      String owner,
                      String repository,
                      String branch,
                      String fromSha);

    void createOrUpdateFile(String accessToken,
                            String owner,
                            String repository,
                            String branch,
                            String path,
                            String base64Content,
                            String commitMessage);

    PullRequestRef createPullRequest(String accessToken,
                                     String upstreamOwner,
                                     String upstreamRepository,
                                     String title,
                                     String body,
                                     String headOwner,
                                     String headBranch,
                                     String baseBranch);

    PullRequestStatus getPullRequestStatus(String owner,
                                           String repository,
                                           long pullRequestNumber);

    boolean pathExistsInBranch(String owner,
                               String repository,
                               String branch,
                               String path);

    List<String> listDirectoryNamesInBranch(String owner,
                                            String repository,
                                            String branch,
                                            String path);

    record ForkRef(String owner, String repository) {
    }

    record PullRequestRef(long number, String htmlUrl) {
    }

    enum PullRequestStatus {
        OPEN,
        MERGED,
        CLOSED_UNMERGED
    }
}
