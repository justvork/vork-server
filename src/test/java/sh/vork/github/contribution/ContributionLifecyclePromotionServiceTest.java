package sh.vork.github.contribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.artifact.ArtifactStatus;
import sh.vork.orm.DatabaseRepository;
import sh.vork.oauth.OAuthTemplateEntity;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.InvocationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;
import sh.vork.skill.SkillGroup;
import sh.vork.surface.Surface;

@ExtendWith(MockitoExtension.class)
class ContributionLifecyclePromotionServiceTest {

    @Mock
    private DatabaseRepository<AgentTemplate> agentRepository;

    @Mock
    private DatabaseRepository<ScheduledJob> jobRepository;

    @Mock
    private DatabaseRepository<Surface> surfaceRepository;

        @Mock
        private DatabaseRepository<SkillGroup> skillGroupRepository;

        @Mock
        private DatabaseRepository<ReflectionGroup> reflectionGroupRepository;

        @Mock
        private DatabaseRepository<OAuthTemplateEntity> oauthTemplateRepository;

        @Mock
        private DatabaseRepository<ContributionSubmission> contributionSubmissionRepository;

    @Mock
    private GitHubContributionApiClient contributionApiClient;

    @Test
    void reconcilePromotesSubmittedAgentToStagedWhenPathExists() {
        ContributionLifecyclePromotionService service = new ContributionLifecyclePromotionService(
                agentRepository,
                jobRepository,
                surfaceRepository,
                skillGroupRepository,
                reflectionGroupRepository,
                oauthTemplateRepository,
                contributionSubmissionRepository,
                contributionApiClient);

        AgentTemplate submitted = new AgentTemplate(
                "demo-core-1.0",
                "Demo",
                "prompt",
                List.of(),
                false,
                List.of(),
                AgentType.INTERACTIVE,
                List.of(),
                List.of(),
                List.of(),
                null,
                "demo",
                "core",
                "1.0",
                ArtifactStatus.SUBMITTED);

        when(agentRepository.count()).thenReturn(1L);
        when(agentRepository.list(0, 200)).thenReturn(Stream.of(submitted));
        when(contributionApiClient.pathExistsInBranch(
                eq("justvork"),
                eq("vork-central"),
                eq("staging"),
                eq("agents/demo/core/1.0/agent.json")))
                .thenReturn(true);

        ContributionLifecyclePromotionService.PromotionSummary summary = service.reconcileLifecycleStatuses();

        assertEquals(1, summary.agentsPromotedToStaged());
        assertEquals(0, summary.agentsPromotedToPublished());

        ArgumentCaptor<AgentTemplate> captor = ArgumentCaptor.forClass(AgentTemplate.class);
        verify(agentRepository).save(captor.capture());
        assertEquals(ArtifactStatus.STAGED, captor.getValue().artifactStatus());
    }

    @Test
    void reconcilePromotesStagedJobToPublishedWhenPathExistsInMain() {
        ContributionLifecyclePromotionService service = new ContributionLifecyclePromotionService(
                agentRepository,
                jobRepository,
                surfaceRepository,
                skillGroupRepository,
                reflectionGroupRepository,
                oauthTemplateRepository,
                contributionSubmissionRepository,
                contributionApiClient);

        ScheduledJob staged = new ScheduledJob(
                "ops-maint-1.2",
                "Ops Maintenance",
                "Run maintenance",
                "",
                "alice",
                InvocationType.MANUAL,
                null,
                0,
                DurationType.MINUTES,
                0L,
                0L,
                null,
                null,
                null,
                60,
                "",
                ScheduledJobStatus.WAITING,
                List.of(),
                List.of(),
                List.of(),
                "ops",
                "maint",
                "1.2",
                sh.vork.artifact.ArtifactStatus.STAGED);

        when(jobRepository.count()).thenReturn(1L);
        when(jobRepository.list(0, 200)).thenReturn(Stream.of(staged));
        when(contributionApiClient.pathExistsInBranch(
                eq("justvork"),
                eq("vork-central"),
                eq("main"),
                eq("jobs/ops/maint/1.2/job.json")))
                .thenReturn(true);

        ContributionLifecyclePromotionService.PromotionSummary summary = service.reconcileLifecycleStatuses();

        assertEquals(1, summary.jobsPromotedToPublished());
        assertEquals(0, summary.jobsPromotedToStaged());

        ArgumentCaptor<ScheduledJob> captor = ArgumentCaptor.forClass(ScheduledJob.class);
        verify(jobRepository).save(captor.capture());
        assertEquals(sh.vork.artifact.ArtifactStatus.PUBLISHED, captor.getValue().artifactStatus());
    }

    @Test
    void reconcileDoesNotPromoteWhenBranchPathMissingOrCheckFails() {
        ContributionLifecyclePromotionService service = new ContributionLifecyclePromotionService(
                agentRepository,
                jobRepository,
                surfaceRepository,
                skillGroupRepository,
                reflectionGroupRepository,
                oauthTemplateRepository,
                contributionSubmissionRepository,
                contributionApiClient);

        Surface submitted = new Surface(
                "ux-shell-2.0",
                "uxshell",
                "UX Shell",
                "desc",
                "session-1",
                "",
                List.of(),
                List.of(),
                List.of(),
                false,
                "",
                List.of(),
                Surface.AccessPolicy.defaultPolicy(),
                "ux",
                "shell",
                "2.0",
                sh.vork.artifact.ArtifactStatus.SUBMITTED,
                1L,
                2L);

        when(surfaceRepository.count()).thenReturn(1L);
        when(surfaceRepository.list(0, 200)).thenReturn(Stream.of(submitted));
        when(contributionApiClient.pathExistsInBranch(
                eq("justvork"),
                eq("vork-central"),
                eq("staging"),
                eq("surfaces/ux/shell/2.0/surface.json")))
                .thenThrow(new IllegalStateException("rate limited"));

        ContributionLifecyclePromotionService.PromotionSummary summary = service.reconcileLifecycleStatuses();

        assertEquals(0, summary.surfacesPromotedToStaged());
        assertEquals(0, summary.surfacesPromotedToPublished());
        verify(surfaceRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void reconcileMarksSubmittedSurfaceAsRejectedWhenPrClosedUnmerged() {
        ContributionLifecyclePromotionService service = new ContributionLifecyclePromotionService(
                agentRepository,
                jobRepository,
                surfaceRepository,
                skillGroupRepository,
                reflectionGroupRepository,
                oauthTemplateRepository,
                contributionSubmissionRepository,
                contributionApiClient);

        Surface submitted = new Surface(
                "ux-shell-2.0",
                "uxshell",
                "UX Shell",
                "desc",
                "session-1",
                "",
                List.of(),
                List.of(),
                List.of(),
                false,
                "",
                List.of(),
                Surface.AccessPolicy.defaultPolicy(),
                "ux",
                "shell",
                "2.0",
                sh.vork.artifact.ArtifactStatus.SUBMITTED,
                1L,
                2L);

        when(surfaceRepository.count()).thenReturn(1L);
        when(surfaceRepository.list(0, 200)).thenReturn(Stream.of(submitted));
        when(contributionSubmissionRepository.get("surface:ux-shell-2.0"))
                .thenReturn(new ContributionSubmission(
                        "surface:ux-shell-2.0",
                        "surface",
                        "ux-shell-2.0",
                        "justvork",
                        "vork-central",
                        "staging",
                        "contrib/surface/ux-shell-2.0",
                        77L,
                        "https://github.com/justvork/vork-central/pull/77",
                        1L,
                        2L));
        when(contributionApiClient.getPullRequestStatus("justvork", "vork-central", 77L))
                .thenReturn(GitHubContributionApiClient.PullRequestStatus.CLOSED_UNMERGED);

        ContributionLifecyclePromotionService.PromotionSummary summary = service.reconcileLifecycleStatuses();

        assertEquals(1, summary.surfacesPromotedToRejected());
        assertEquals(0, summary.surfacesPromotedToStaged());
        ArgumentCaptor<Surface> captor = ArgumentCaptor.forClass(Surface.class);
        verify(surfaceRepository).save(captor.capture());
        assertEquals(sh.vork.artifact.ArtifactStatus.REJECTED, captor.getValue().artifactStatus());
    }

        @Test
        void reconcilePromotesSubmittedSkillGroupToStagedWhenPathExists() {
                ContributionLifecyclePromotionService service = new ContributionLifecyclePromotionService(
                                agentRepository,
                                jobRepository,
                                surfaceRepository,
                                skillGroupRepository,
                                reflectionGroupRepository,
                                oauthTemplateRepository,
                                contributionSubmissionRepository,
                                contributionApiClient);

                SkillGroup submitted = new SkillGroup(
                                "demo-skillset-1.0",
                                "Demo Skills",
                                "alice",
                                "Integration",
                                List.of(),
                                "demo",
                                "skillset",
                                "1.0",
                                sh.vork.artifact.ArtifactStatus.SUBMITTED,
                                1L,
                                2L);

                when(skillGroupRepository.count()).thenReturn(1L);
                when(skillGroupRepository.list(0, 200)).thenReturn(Stream.of(submitted));
                when(contributionApiClient.pathExistsInBranch(
                                eq("justvork"),
                                eq("vork-central"),
                                eq("staging"),
                                eq("skills/demo/skillset/1.0/skills.json")))
                                .thenReturn(true);

                ContributionLifecyclePromotionService.PromotionSummary summary = service.reconcileLifecycleStatuses();

                assertEquals(1, summary.skillsPromotedToStaged());
                assertEquals(0, summary.skillsPromotedToPublished());
                ArgumentCaptor<SkillGroup> captor = ArgumentCaptor.forClass(SkillGroup.class);
                verify(skillGroupRepository).save(captor.capture());
                assertEquals(sh.vork.artifact.ArtifactStatus.STAGED, captor.getValue().artifactStatus());
        }

    @Test
    void reconcilePromotesSubmittedOAuthTemplateToPublishedWhenPathExistsInMain() {
        ContributionLifecyclePromotionService service = new ContributionLifecyclePromotionService(
                agentRepository,
                jobRepository,
                surfaceRepository,
                skillGroupRepository,
                reflectionGroupRepository,
                oauthTemplateRepository,
                contributionSubmissionRepository,
                contributionApiClient);

        OAuthTemplateEntity submitted = new OAuthTemplateEntity(
                "oauth-template-1",
                "Google OAuth",
                "google_oauth",
                "desc",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                List.of("openid"),
                java.util.Map.of(),
                sh.vork.artifact.ArtifactStatus.SUBMITTED,
                1L,
                2L);

        when(oauthTemplateRepository.count()).thenReturn(1L);
        when(oauthTemplateRepository.list(0, 200)).thenReturn(Stream.of(submitted));
        when(contributionApiClient.pathExistsInBranch(
                eq("justvork"),
                eq("vork-central"),
                eq("main"),
                eq("oauth-templates/google_oauth.json")))
                .thenReturn(true);

        ContributionLifecyclePromotionService.PromotionSummary summary = service.reconcileLifecycleStatuses();

        assertEquals(1, summary.oauthTemplatesPromotedToPublished());
        ArgumentCaptor<OAuthTemplateEntity> captor = ArgumentCaptor.forClass(OAuthTemplateEntity.class);
        verify(oauthTemplateRepository).save(captor.capture());
        assertEquals(sh.vork.artifact.ArtifactStatus.PUBLISHED, captor.getValue().artifactStatus());
    }
}
