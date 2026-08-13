package sh.vork.github.contribution;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import sh.vork.github.auth.ContributionAuthProvider;
import sh.vork.github.auth.ContributionAuthToken;

/**
 * Auth-agnostic contribution workflow for fork -> branch -> commit -> PR.
 */
@Service
public class GitHubForkContributionService {

    private static final Logger log = LoggerFactory.getLogger(GitHubForkContributionService.class);

    private final ContributionAuthProvider authProvider;
    private final GitHubContributionApiClient apiClient;

    public GitHubForkContributionService(ContributionAuthProvider authProvider,
                                         GitHubContributionApiClient apiClient) {
        this.authProvider = authProvider;
        this.apiClient = apiClient;
    }

    public ContributionSubmitResult submitContribution(ContributionSubmitRequest request) {
        log.debug("ENTER submitContribution: localUsername={}, branchName={}",
                request == null ? null : request.localUsername(),
                request == null ? null : request.branchName());
        String validationError = validate(request);
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }

        ContributionAuthToken token = authProvider.requireToken(request.localUsername());
        String accessToken = token.accessToken();

        ContributionTarget target = request.target();
        String upstreamOwner = target.owner().trim();
        String upstreamRepository = target.repository().trim();
        String baseBranch = target.baseBranch().trim();

        String githubLogin = apiClient.getAuthenticatedLogin(accessToken);
        log.debug("Step 1: resolved GitHub login [localUsername={}, githubLogin={}]",
                request.localUsername(), githubLogin);

        GitHubContributionApiClient.ForkRef fork = apiClient.ensureFork(
                accessToken,
                upstreamOwner,
                upstreamRepository);
        log.debug("Step 2: ensured fork [forkOwner={}, forkRepo={}]", fork.owner(), fork.repository());

        String effectiveBaseBranch = baseBranch;
        String baseSha = apiClient.getBranchHeadSha(accessToken, upstreamOwner, upstreamRepository, effectiveBaseBranch);
        log.debug("Step 3: resolved upstream base branch sha [upstream={}/{}, branch={}, sha={}]",
            upstreamOwner, upstreamRepository, effectiveBaseBranch, baseSha);

        apiClient.createBranch(accessToken, fork.owner(), fork.repository(), request.branchName().trim(), baseSha);
        log.debug("Step 4: created branch [branch={}]", request.branchName());

        List<ContributionFile> files = request.files();
        for (ContributionFile file : files) {
            String encoded = Base64.getEncoder().encodeToString(file.content().getBytes(StandardCharsets.UTF_8));
            apiClient.createOrUpdateFile(
                    accessToken,
                    fork.owner(),
                    fork.repository(),
                    request.branchName().trim(),
                    file.path().trim(),
                    encoded,
                    request.commitMessage().trim());
            log.debug("Step 5: committed file [branch={}, path={}]", request.branchName(), file.path());
        }

        GitHubContributionApiClient.PullRequestRef pr = apiClient.createPullRequest(
                accessToken,
                upstreamOwner,
                upstreamRepository,
                request.prTitle().trim(),
                request.prBody() == null ? "" : request.prBody().trim(),
                fork.owner(),
                request.branchName().trim(),
                effectiveBaseBranch);
        log.info("Contribution PR created [upstream={}/{}, fork={}/{}, branch={}, prNumber={}]",
                upstreamOwner, upstreamRepository, fork.owner(), fork.repository(),
                request.branchName(), pr.number());

        ContributionSubmitResult result = new ContributionSubmitResult(
                upstreamOwner,
                upstreamRepository,
                effectiveBaseBranch,
                fork.owner(),
                fork.repository(),
                request.branchName().trim(),
                pr.number(),
                pr.htmlUrl());
        log.debug("EXIT submitContribution: prUrl={}", result.pullRequestUrl());
        return result;
    }

    private static String validate(ContributionSubmitRequest request) {
        if (request == null) {
            return "Contribution request is required.";
        }
        if (isBlank(request.localUsername())) {
            return "localUsername is required.";
        }
        if (isBlank(request.branchName())) {
            return "branchName is required.";
        }
        if (isBlank(request.commitMessage())) {
            return "commitMessage is required.";
        }
        if (isBlank(request.prTitle())) {
            return "prTitle is required.";
        }
        if (request.target() == null) {
            return "target is required.";
        }
        if (isBlank(request.target().owner())) {
            return "target.owner is required.";
        }
        if (isBlank(request.target().repository())) {
            return "target.repository is required.";
        }
        if (isBlank(request.target().baseBranch())) {
            return "target.baseBranch is required.";
        }
        if (request.files() == null || request.files().isEmpty()) {
            return "At least one contribution file is required.";
        }
        for (ContributionFile file : request.files()) {
            if (file == null || isBlank(file.path())) {
                return "Each file must include a path.";
            }
            if (file.content() == null) {
                return "Each file must include non-null content.";
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
