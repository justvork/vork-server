package sh.vork.github.contribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sh.vork.github.auth.ContributionAuthProvider;
import sh.vork.github.auth.ContributionAuthToken;

@ExtendWith(MockitoExtension.class)
class GitHubForkContributionServiceTest {

    @Mock
    private ContributionAuthProvider authProvider;

    @Mock
    private GitHubContributionApiClient apiClient;

    private GitHubForkContributionService service;

    @BeforeEach
    void setUp() {
        service = new GitHubForkContributionService(authProvider, apiClient);
    }

    @Test
    void submitContributionRunsForkBranchCommitAndPrFlow() {
        ContributionSubmitRequest request = new ContributionSubmitRequest(
                "alice",
                "contrib/agent-1-0",
                "feat: add agent artifact",
                "Add agent artifact",
                "This PR adds a new agent artifact.",
                new ContributionTarget("justvork", "vork-central", "staging"),
                List.of(new ContributionFile("agents/demo/core/1.0/agent.json", "{\"name\":\"demo\"}")));

        when(authProvider.requireToken("alice"))
                .thenReturn(new ContributionAuthToken("github-device-flow", "alice", "octocat", "token-1", 0L));
        when(apiClient.getAuthenticatedLogin("token-1")).thenReturn("octocat");
        when(apiClient.ensureFork("token-1", "justvork", "vork-central"))
                .thenReturn(new GitHubContributionApiClient.ForkRef("octocat", "vork-central"));
        when(apiClient.getBranchHeadSha("token-1", "justvork", "vork-central", "staging")).thenReturn("abc123");
        when(apiClient.createPullRequest(
                "token-1",
                "justvork",
                "vork-central",
                "Add agent artifact",
                "This PR adds a new agent artifact.",
                "octocat",
                "contrib/agent-1-0",
                "staging"))
                .thenReturn(new GitHubContributionApiClient.PullRequestRef(42L, "https://github.com/justvork/vork-central/pull/42"));

        ContributionSubmitResult result = service.submitContribution(request);

        assertEquals(42L, result.pullRequestNumber());
        assertEquals("https://github.com/justvork/vork-central/pull/42", result.pullRequestUrl());
        verify(apiClient).createBranch("token-1", "octocat", "vork-central", "contrib/agent-1-0", "abc123");
        verify(apiClient).createOrUpdateFile(anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(apiClient).createPullRequest(
                "token-1",
                "justvork",
                "vork-central",
                "Add agent artifact",
                "This PR adds a new agent artifact.",
                "octocat",
                "contrib/agent-1-0",
                "staging");
    }

    @Test
    void submitContributionRejectsInvalidRequest() {
        ContributionSubmitRequest invalid = new ContributionSubmitRequest(
                "alice",
                " ",
                "msg",
                "title",
                "body",
                new ContributionTarget("justvork", "vork-central", "staging"),
                List.of(new ContributionFile("a/b/c.json", "{}")));

        assertThrows(IllegalArgumentException.class, () -> service.submitContribution(invalid));
    }

        @Test
        void submitContributionFailsWhenRequestedBaseBranchIsMissing() {
        ContributionSubmitRequest request = new ContributionSubmitRequest(
                "alice",
                "contrib/agent-1-0",
                "feat: add agent artifact",
                "Add agent artifact",
                "This PR adds a new agent artifact.",
                new ContributionTarget("justvork", "vork-central", "staging"),
                List.of(new ContributionFile("agents/demo/core/1.0/agent.json", "{\"name\":\"demo\"}")));

        when(authProvider.requireToken("alice"))
                .thenReturn(new ContributionAuthToken("github-device-flow", "alice", "octocat", "token-1", 0L));
        when(apiClient.getAuthenticatedLogin("token-1")).thenReturn("octocat");
        when(apiClient.ensureFork("token-1", "justvork", "vork-central"))
                .thenReturn(new GitHubContributionApiClient.ForkRef("octocat", "vork-central"));
        when(apiClient.getBranchHeadSha("token-1", "justvork", "vork-central", "staging"))
                .thenThrow(new IllegalStateException("GitHub API call failed with status 404: {\"message\":\"Not Found\"}"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.submitContribution(request));
        assertTrue(ex.getMessage().contains("status 404"));
    }
}
