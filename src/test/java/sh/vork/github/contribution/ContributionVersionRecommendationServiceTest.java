package sh.vork.github.contribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ContributionVersionRecommendationServiceTest {

    @Mock
    private GitHubContributionApiClient contributionApiClient;

    @Test
    void recommendDefaultsToOneZeroWhenNoVersionsExist() {
        ContributionVersionRecommendationService service =
                new ContributionVersionRecommendationService(contributionApiClient);

        when(contributionApiClient.listDirectoryNamesInBranch(
                "justvork",
                "vork-central",
                "staging",
                "agents/demo/core"))
                .thenReturn(List.of());

        ContributionVersionRecommendationService.Recommendation recommendation =
                service.recommendNextVersion("agents", "demo", "core", false);

        assertNull(recommendation.latestVersion());
        assertEquals("1.0", recommendation.recommendedVersion());
        assertEquals("minor", recommendation.strategy());
    }

    @Test
    void recommendBumpsMinorWhenNoBreakingChange() {
        ContributionVersionRecommendationService service =
                new ContributionVersionRecommendationService(contributionApiClient);

        when(contributionApiClient.listDirectoryNamesInBranch(
                "justvork",
                "vork-central",
                "staging",
                "jobs/ops/maint"))
                .thenReturn(List.of("1.3", "2.1", "invalid", "2.4"));

        ContributionVersionRecommendationService.Recommendation recommendation =
                service.recommendNextVersion("jobs", "ops", "maint", false);

        assertEquals("2.4", recommendation.latestVersion());
        assertEquals("2.5", recommendation.recommendedVersion());
        assertEquals("minor", recommendation.strategy());
    }

    @Test
    void recommendBumpsMajorWhenBreakingChange() {
        ContributionVersionRecommendationService service =
                new ContributionVersionRecommendationService(contributionApiClient);

        when(contributionApiClient.listDirectoryNamesInBranch(
                "justvork",
                "vork-central",
                "staging",
                "surfaces/ux/shell"))
                .thenReturn(List.of("3.9", "3.10"));

        ContributionVersionRecommendationService.Recommendation recommendation =
                service.recommendNextVersion("surfaces", "ux", "shell", true);

        assertEquals("3.10", recommendation.latestVersion());
        assertEquals("4.0", recommendation.recommendedVersion());
        assertEquals("major", recommendation.strategy());
    }

        @Test
        void recommendFallsBackToInitialVersionWhenLookupFails() {
                ContributionVersionRecommendationService service =
                                new ContributionVersionRecommendationService(contributionApiClient);

                when(contributionApiClient.listDirectoryNamesInBranch(
                                "justvork",
                                "vork-central",
                                "staging",
                                "agents/demo/core"))
                                .thenThrow(new IllegalStateException("lookup unavailable"));

                ContributionVersionRecommendationService.Recommendation recommendation =
                                service.recommendNextVersion("agents", "demo", "core", true);

                assertNull(recommendation.latestVersion());
                assertEquals("1.0", recommendation.recommendedVersion());
                assertEquals("major", recommendation.strategy());
        }
}
