package sh.vork.github.contribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.User;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.agent.ArtifactStatus;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.orm.DatabaseRepository;
import sh.vork.oauth.OAuthTemplateEntity;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.scheduling.domain.DurationType;
import sh.vork.scheduling.domain.InvocationType;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.scheduling.domain.ScheduledJobStatus;
import sh.vork.setup.SystemSettingsService;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillGroup;
import sh.vork.surface.Surface;
import sh.vork.surface.service.SurfaceService;

@ExtendWith(MockitoExtension.class)
class ContributionControllerTest {

    @Mock
    private DatabaseRepository<AgentTemplate> agentRepository;

        @Mock
        private DatabaseRepository<ScheduledJob> jobRepository;

        @Mock
        private DatabaseRepository<Surface> surfaceRepository;

        @Mock
        private DatabaseRepository<SkillGroup> skillGroupRepository;

        @Mock
        private DatabaseRepository<Skill> skillRepository;

        @Mock
        private DatabaseRepository<ReflectionGroup> reflectionGroupRepository;

        @Mock
        private DatabaseRepository<Reflection> reflectionRepository;

        @Mock
        private DatabaseRepository<ReflectionBinding> reflectionBindingRepository;

        @Mock
        private DatabaseRepository<OAuthTemplateEntity> oauthTemplateRepository;

        @Mock
        private DatabaseRepository<ContributionSubmission> contributionSubmissionRepository;

    @Mock
    private GitHubForkContributionService contributionService;

        @Mock
        private ContributionLifecyclePromotionService promotionService;

        @Mock
        private ContributionVersionRecommendationService recommendationService;

        @Mock
        private ContributionDependencyValidator dependencyValidator;

        @Mock
        private AiOrchestrationService aiOrchestrationService;

        @Mock
        private SystemSettingsService systemSettingsService;

        @Mock
        private SurfaceService surfaceService;

        @Mock
        private SessionFileSystem sessionFileSystem;

    private ContributionController controller;

    @BeforeEach
    void setUp() {
                controller = new ContributionController(
                                agentRepository,
                                jobRepository,
                                surfaceRepository,
                                skillGroupRepository,
                                skillRepository,
                                reflectionGroupRepository,
                                reflectionRepository,
                                reflectionBindingRepository,
                                oauthTemplateRepository,
                                contributionSubmissionRepository,
                                contributionService,
                                promotionService,
                                recommendationService,
                                dependencyValidator,
                                aiOrchestrationService,
                                systemSettingsService,
                                surfaceService,
                                sessionFileSystem,
                                new ObjectMapper());

                ContributionDependencyValidator.DependencyValidationReport okAgent =
                        new ContributionDependencyValidator.DependencyValidationReport(
                                "agent", "", true, "All dependencies are STAGED.", List.of(), List.of(), List.of());
                ContributionDependencyValidator.DependencyValidationReport okJob =
                        new ContributionDependencyValidator.DependencyValidationReport(
                                "job", "", true, "All dependencies are STAGED.", List.of(), List.of(), List.of());
                ContributionDependencyValidator.DependencyValidationReport okSurface =
                        new ContributionDependencyValidator.DependencyValidationReport(
                                "surface", "", true, "All dependencies are STAGED.", List.of(), List.of(), List.of());
                ContributionDependencyValidator.DependencyValidationReport okSkill =
                        new ContributionDependencyValidator.DependencyValidationReport(
                                "skill-group", "", true, "All dependencies are STAGED.", List.of(), List.of(), List.of());
                ContributionDependencyValidator.DependencyValidationReport okReflection =
                        new ContributionDependencyValidator.DependencyValidationReport(
                                "reflection-group", "", true, "All dependencies are STAGED.", List.of(), List.of(), List.of());

                Mockito.lenient().when(dependencyValidator.validateAgent(anyString())).thenReturn(okAgent);
                Mockito.lenient().when(dependencyValidator.validateJob(anyString())).thenReturn(okJob);
                Mockito.lenient().when(dependencyValidator.validateSurface(anyString())).thenReturn(okSurface);
                Mockito.lenient().when(dependencyValidator.validateSkillGroup(anyString())).thenReturn(okSkill);
                Mockito.lenient().when(dependencyValidator.validateReflectionGroup(anyString())).thenReturn(okReflection);
    }

    @Test
    void publishReflectionBlocksWhenDependencyNotStaged() {
        ReflectionGroup snapshot = new ReflectionGroup(
                "reflection-demo-SNAPSHOT",
                "demo",
                "Demo Reflection",
                "desc",
                sh.vork.reflection.ReflectionType.REST,
                "https://api.example.test",
                true,
                List.of(),
                List.of(),
                sh.vork.reflection.ReflectionAuthenticationMode.OAUTH,
                "oauth-demo",
                "demo",
                "reflection",
                "SNAPSHOT",
                sh.vork.reflection.ArtifactStatus.SNAPSHOT,
                System.currentTimeMillis(),
                System.currentTimeMillis());

        when(reflectionGroupRepository.get("reflection-demo-SNAPSHOT")).thenReturn(snapshot);
        when(dependencyValidator.validateReflectionGroup("reflection-demo-SNAPSHOT"))
                .thenReturn(new ContributionDependencyValidator.DependencyValidationReport(
                        "reflection-group",
                        "reflection-demo-SNAPSHOT",
                        false,
                        "Dependency validation failed for reflection-group reflection-demo-SNAPSHOT.",
                        List.of(),
                        List.of(new ContributionDependencyValidator.DependencyIssue(
                                "oauth-template",
                                "oauth-demo",
                                "Demo OAuth",
                                "SNAPSHOT",
                                "reflection-group:reflection-demo-SNAPSHOT -> oauth-template:oauth-demo",
                                "Dependency status must be STAGED or PUBLISHED before PR generation.")),
                        List.of()));

        ContributionController.PublishRequest request = new ContributionController.PublishRequest(
                "1.0",
                "feat: publish reflection",
                "Publish reflection",
                "Body",
                "Summary",
                false,
                "",
                "");

        ResponseEntity<?> response = controller.publishReflection(
                "reflection-demo-SNAPSHOT",
                request,
                User.withUsername("alice").password("x").authorities("USERS_MANAGE").build());

        assertEquals(409, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getBody();
        assertNotNull(payload);
        assertNotNull(payload.get("dependencyReport"));
        verify(contributionService, never()).submitContribution(any());
    }

    @Test
    void publishAgentSubmitsPrAndTransitionsArtifactToSubmitted() {
        AgentTemplate snapshot = new AgentTemplate(
                "demo-core-SNAPSHOT",
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
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT);

        when(agentRepository.get("demo-core-SNAPSHOT")).thenReturn(snapshot);
        when(agentRepository.get("demo-core-1.0")).thenReturn(null);
        when(contributionService.submitContribution(any()))
                .thenReturn(new ContributionSubmitResult(
                        "justvork",
                        "vork-central",
                        "staging",
                        "octocat",
                        "vork-central",
                        "contrib/agent/demo-core-1.0",
                        42L,
                        "https://github.com/justvork/vork-central/pull/42"));

        ContributionController.PublishRequest request = new ContributionController.PublishRequest(
                "1.0",
                "feat: publish demo core",
                "Publish demo/core 1.0",
                "Body",
                "Summary",
                false,
                "",
                "");

        ResponseEntity<?> response = controller.publishAgent(
                "demo-core-SNAPSHOT",
                request,
                User.withUsername("alice").password("x").authorities("AGENTS_WRITE").build());

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getBody();
        assertEquals("ok", payload.get("status"));
        assertNotNull(payload.get("artifact"));

        ArgumentCaptor<ContributionSubmitRequest> reqCaptor = ArgumentCaptor.forClass(ContributionSubmitRequest.class);
        verify(contributionService).submitContribution(reqCaptor.capture());
        assertEquals("alice", reqCaptor.getValue().localUsername());
        assertEquals("Publish demo/core 1.0", reqCaptor.getValue().prTitle());

        verify(agentRepository).save(any(AgentTemplate.class));
        verify(agentRepository).delete("demo-core-SNAPSHOT");
    }

    @Test
    void publishJobSubmitsPrAndTransitionsArtifactToSubmitted() {
        ScheduledJob snapshot = new ScheduledJob(
                "ops-maint-SNAPSHOT",
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
                "SNAPSHOT",
                sh.vork.scheduling.domain.ArtifactStatus.SNAPSHOT);

        when(jobRepository.get("ops-maint-SNAPSHOT")).thenReturn(snapshot);
        when(jobRepository.get("ops-maint-1.2")).thenReturn(null);
        when(contributionService.submitContribution(any()))
                .thenReturn(new ContributionSubmitResult(
                        "justvork",
                        "vork-central",
                        "staging",
                        "octocat",
                        "vork-central",
                        "contrib/job/ops-maint-1.2",
                        55L,
                        "https://github.com/justvork/vork-central/pull/55"));

        ContributionController.PublishRequest request = new ContributionController.PublishRequest(
                "1.2",
                "feat: publish ops job",
                "Publish ops/maint 1.2",
                "Job PR body",
                "Job summary",
                false,
                "",
                "");

        ResponseEntity<?> response = controller.publishJob(
                "ops-maint-SNAPSHOT",
                request,
                User.withUsername("alice").password("x").authorities("USERS_MANAGE").build());

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getBody();
        assertEquals("ok", payload.get("status"));

        verify(jobRepository).save(any(ScheduledJob.class));
        verify(jobRepository).delete("ops-maint-SNAPSHOT");
    }

    @Test
    void publishSurfaceSubmitsPrWithAssetFiles() throws Exception {
        Surface snapshot = new Surface(
                "ux-shell-SNAPSHOT",
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
                "SNAPSHOT",
                sh.vork.surface.ArtifactStatus.SNAPSHOT,
                System.currentTimeMillis(),
                System.currentTimeMillis());

        when(surfaceRepository.get("ux-shell-SNAPSHOT")).thenReturn(snapshot);
        when(surfaceRepository.get("ux-shell-2.0")).thenReturn(null);
        when(surfaceService.ensureSession("ux-shell-SNAPSHOT", "alice")).thenReturn(new sh.vork.ai.entity.AiSession(
                "session-1",
                "GEMINI",
                sh.vork.ai.entity.SessionOriginMode.WEB,
                "alice",
                "Surface",
                1L,
                0,
                List.of(),
                Map.of(),
                sh.vork.ai.entity.AiSessionStatus.RUNNING,
                null,
                null,
                List.of(),
                List.of(),
                List.of()));
        when(sessionFileSystem.read(FileArea.SESSION, "session-1", "index.html"))
                .thenReturn(new ByteArrayInputStream("<html><body>hi</body></html>".getBytes()));
        when(sessionFileSystem.read(FileArea.SESSION, "session-1", "script.js"))
                .thenReturn(new ByteArrayInputStream("console.log('ok');".getBytes()));
        when(contributionService.submitContribution(any()))
                .thenReturn(new ContributionSubmitResult(
                        "justvork",
                        "vork-central",
                        "staging",
                        "octocat",
                        "vork-central",
                        "contrib/surface/ux-shell-2.0",
                        77L,
                        "https://github.com/justvork/vork-central/pull/77"));

        ContributionController.PublishRequest request = new ContributionController.PublishRequest(
                "2.0",
                "feat: publish ux shell",
                "Publish ux/shell 2.0",
                "Surface PR body",
                "Surface summary",
                false,
                "",
                "");

        ResponseEntity<?> response = controller.publishSurface(
                "ux-shell-SNAPSHOT",
                request,
                User.withUsername("alice").password("x").authorities("USERS_MANAGE").build());

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getBody();
        assertEquals("ok", payload.get("status"));

        ArgumentCaptor<ContributionSubmitRequest> reqCaptor = ArgumentCaptor.forClass(ContributionSubmitRequest.class);
        verify(contributionService).submitContribution(reqCaptor.capture());
        assertEquals(3, reqCaptor.getValue().files().size());

        ContributionFile surfaceJsonFile = reqCaptor.getValue().files().stream()
                .filter(file -> file.path().endsWith("/surface.json"))
                .findFirst()
                .orElseThrow();
        String surfaceJson = surfaceJsonFile.content();
        assertTrue(!surfaceJson.contains("\"published\""));
        assertTrue(!surfaceJson.contains("\"assignedUserUuids\""));
        assertTrue(surfaceJson.contains("\"accessPolicy\""));

        verify(sessionFileSystem).read(eq(FileArea.SESSION), eq("session-1"), eq("index.html"));
        verify(sessionFileSystem).read(eq(FileArea.SESSION), eq("session-1"), eq("script.js"));
        verify(surfaceRepository).save(any(Surface.class));
        verify(surfaceRepository).delete("ux-shell-SNAPSHOT");
    }

        @Test
        void createAgentSnapshotClonesImmutableAgentToSnapshot() {
                AgentTemplate immutable = new AgentTemplate(
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

                when(agentRepository.get("demo-core-1.0")).thenReturn(immutable);
                when(agentRepository.get("demo-core-SNAPSHOT")).thenReturn(null);

                ResponseEntity<?> response = controller.createAgentSnapshot("demo-core-1.0");

                assertEquals(200, response.getStatusCode().value());
                AgentTemplate cloned = (AgentTemplate) response.getBody();
                assertEquals("demo-core-SNAPSHOT", cloned.uuid());
                assertEquals("SNAPSHOT", cloned.version());
                assertEquals(ArtifactStatus.SNAPSHOT, cloned.artifactStatus());
                verify(agentRepository).save(any(AgentTemplate.class));
        }

        @Test
        void createJobSnapshotClonesImmutableJobToSnapshot() {
                ScheduledJob immutable = new ScheduledJob(
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
                                sh.vork.scheduling.domain.ArtifactStatus.SUBMITTED);

                when(jobRepository.get("ops-maint-1.2")).thenReturn(immutable);
                when(jobRepository.get("ops-maint-SNAPSHOT")).thenReturn(null);

                ResponseEntity<?> response = controller.createJobSnapshot(
                                "ops-maint-1.2",
                                User.withUsername("alice").password("x").authorities("USERS_MANAGE").build());

                assertEquals(200, response.getStatusCode().value());
                ScheduledJob cloned = (ScheduledJob) response.getBody();
                assertEquals("ops-maint-SNAPSHOT", cloned.id());
                assertEquals("SNAPSHOT", cloned.version());
                assertEquals(sh.vork.scheduling.domain.ArtifactStatus.SNAPSHOT, cloned.artifactStatus());
                verify(jobRepository).save(any(ScheduledJob.class));
        }

        @Test
        void createSurfaceSnapshotClonesImmutableSurfaceToSnapshot() {
                Surface immutable = new Surface(
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
                                sh.vork.surface.ArtifactStatus.SUBMITTED,
                                System.currentTimeMillis(),
                                System.currentTimeMillis());

                when(surfaceRepository.get("ux-shell-2.0")).thenReturn(immutable);
                when(surfaceRepository.get("ux-shell-SNAPSHOT")).thenReturn(null);

                ResponseEntity<?> response = controller.createSurfaceSnapshot("ux-shell-2.0");

                assertEquals(200, response.getStatusCode().value());
                Surface cloned = (Surface) response.getBody();
                assertEquals("ux-shell-SNAPSHOT", cloned.uuid());
                assertEquals("SNAPSHOT", cloned.version());
                assertEquals(sh.vork.surface.ArtifactStatus.SNAPSHOT, cloned.artifactStatus());
                verify(surfaceRepository).save(any(Surface.class));
        }

        @Test
        void reconcileContributionPromotionsReturnsSummary() {
                ContributionLifecyclePromotionService.PromotionSummary summary =
                                new ContributionLifecyclePromotionService.PromotionSummary(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18);
                when(promotionService.reconcileLifecycleStatuses()).thenReturn(summary);

                ResponseEntity<?> response = controller.reconcileContributionPromotions();

                assertEquals(200, response.getStatusCode().value());
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) response.getBody();
                assertEquals("ok", payload.get("status"));
                assertEquals(summary, payload.get("summary"));
                verify(promotionService).reconcileLifecycleStatuses();
        }

        @Test
        void recommendAgentVersionReturnsRecommendation() {
                AgentTemplate snapshot = new AgentTemplate(
                                "demo-core-SNAPSHOT",
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
                                "SNAPSHOT",
                                ArtifactStatus.SNAPSHOT);
                when(agentRepository.get("demo-core-SNAPSHOT")).thenReturn(snapshot);
                ContributionVersionRecommendationService.Recommendation recommendation =
                                new ContributionVersionRecommendationService.Recommendation("1.4", "1.5", "minor");
                when(recommendationService.recommendNextVersion("agents", "demo", "core", false))
                                .thenReturn(recommendation);

                ResponseEntity<?> response = controller.recommendAgentVersion(
                                "demo-core-SNAPSHOT",
                                new ContributionController.VersionRecommendationRequest(false, "small update"));

                assertEquals(200, response.getStatusCode().value());
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) response.getBody();
                assertEquals("ok", payload.get("status"));
                assertEquals(recommendation, payload.get("recommendation"));
                verify(recommendationService).recommendNextVersion("agents", "demo", "core", false);
        }

    @Test
    void publishSkillGroupSubmitsPrAndTransitionsArtifactToSubmitted() {
        Skill skill = new Skill(
                "skill-1",
                "Summarize",
                "desc",
                "demo-skillset-SNAPSHOT",
                sh.vork.skill.SkillVisibility.PUBLIC,
                List.of(),
                "instructions",
                List.of(),
                List.of(),
                List.of(),
                null,
                "none",
                "",
                1,
                1L,
                1L,
                List.of(),
                List.of());

        SkillGroup snapshot = new SkillGroup(
                "demo-skillset-SNAPSHOT",
                "Demo Skills",
                "alice",
                "Integration",
                List.of(skill),
                "demo",
                "skillset",
                "SNAPSHOT",
                sh.vork.skill.ArtifactStatus.SNAPSHOT,
                1L,
                1L);

        when(skillGroupRepository.get("demo-skillset-SNAPSHOT")).thenReturn(snapshot);
        when(skillGroupRepository.get("demo-skillset-1.1")).thenReturn(null);
        when(contributionService.submitContribution(any()))
                .thenReturn(new ContributionSubmitResult(
                        "justvork",
                        "vork-central",
                        "staging",
                        "octocat",
                        "vork-central",
                        "contrib/skill/demo-skillset-1.1",
                        88L,
                        "https://github.com/justvork/vork-central/pull/88"));

        ContributionController.PublishRequest request = new ContributionController.PublishRequest(
                "1.1",
                "feat: publish demo skillset",
                "Publish demo/skillset 1.1",
                "Skill PR body",
                "Skill summary",
                false,
                "",
                "");

        ResponseEntity<?> response = controller.publishSkill(
                "demo-skillset-SNAPSHOT",
                request,
                User.withUsername("alice").password("x").authorities("SKILLS_WRITE").build());

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getBody();
        assertEquals("ok", payload.get("status"));

        ArgumentCaptor<ContributionSubmitRequest> reqCaptor = ArgumentCaptor.forClass(ContributionSubmitRequest.class);
        verify(contributionService).submitContribution(reqCaptor.capture());
        assertEquals("skills/demo/skillset/1.1/skills.json", reqCaptor.getValue().files().get(0).path());

        verify(skillGroupRepository).save(any(SkillGroup.class));
        verify(skillRepository).save(any(Skill.class));
        verify(skillGroupRepository).delete("demo-skillset-SNAPSHOT");
    }

    @Test
    void createSkillSnapshotClonesImmutableGroupToSnapshot() {
        Skill existingSkill = new Skill(
                "skill-1",
                "Extract",
                "desc",
                "demo-skillset-1.0",
                sh.vork.skill.SkillVisibility.PUBLIC,
                List.of(),
                "instructions",
                List.of(),
                List.of(),
                List.of(),
                null,
                "none",
                "",
                1,
                1L,
                1L,
                List.of(),
                List.of());

        SkillGroup immutable = new SkillGroup(
                "demo-skillset-1.0",
                "Demo Skills",
                "alice",
                "Integration",
                List.of(existingSkill),
                "demo",
                "skillset",
                "1.0",
                sh.vork.skill.ArtifactStatus.PUBLISHED,
                1L,
                1L);

        when(skillGroupRepository.get("demo-skillset-1.0")).thenReturn(immutable);
        when(skillGroupRepository.get("demo-skillset-SNAPSHOT")).thenReturn(null);

        ResponseEntity<?> response = controller.createSkillSnapshot("demo-skillset-1.0");

        assertEquals(200, response.getStatusCode().value());
        SkillGroup cloned = (SkillGroup) response.getBody();
        assertEquals("demo-skillset-SNAPSHOT", cloned.uuid());
        assertEquals("SNAPSHOT", cloned.version());
        assertEquals(sh.vork.skill.ArtifactStatus.SNAPSHOT, cloned.artifactStatus());
        verify(skillRepository).save(any(Skill.class));
        verify(skillGroupRepository).save(any(SkillGroup.class));
    }

    @Test
    void publishOAuthTemplateSubmitsPrAndMarksTemplateSubmitted() {
        OAuthTemplateEntity snapshot = new OAuthTemplateEntity(
                "oauth-template-1",
                "Google Workspace OAuth",
                "google_workspace_oauth",
                "Google OAuth defaults",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                List.of("openid", "email"),
                Map.of("access_type", "offline"),
                sh.vork.oauth.ArtifactStatus.SNAPSHOT,
                1L,
                1L);
        when(oauthTemplateRepository.get("oauth-template-1")).thenReturn(snapshot);
        when(contributionService.submitContribution(any()))
                .thenReturn(new ContributionSubmitResult(
                        "justvork",
                        "vork-central",
                        "staging",
                        "octocat",
                        "vork-central",
                        "contrib/oauth-template/google-workspace-123",
                        99L,
                        "https://github.com/justvork/vork-central/pull/99"));

        ContributionController.PublishMetadataRequest request = new ContributionController.PublishMetadataRequest(
                "feat: publish oauth template",
                "Publish Google Workspace OAuth template",
                "Body",
                "Add provider template",
                "- note",
                "- hint");

        ResponseEntity<?> response = controller.publishOAuthTemplate(
                "oauth-template-1",
                request,
                User.withUsername("alice").password("x").authorities("USERS_MANAGE").build());

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) response.getBody();
        assertEquals("ok", payload.get("status"));

        ArgumentCaptor<ContributionSubmitRequest> reqCaptor = ArgumentCaptor.forClass(ContributionSubmitRequest.class);
        verify(contributionService).submitContribution(reqCaptor.capture());
        assertEquals("oauth-templates/google_workspace_oauth.json", reqCaptor.getValue().files().get(0).path());
        verify(oauthTemplateRepository).save(any(OAuthTemplateEntity.class));
        verify(contributionSubmissionRepository).save(any(ContributionSubmission.class));
    }

    @Test
    void publishAgentIncludesLogoWhenProvided() {
        AgentTemplate snapshot = new AgentTemplate(
                "demo-core-SNAPSHOT",
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
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT);

        when(agentRepository.get("demo-core-SNAPSHOT")).thenReturn(snapshot);
        when(agentRepository.get("demo-core-1.0")).thenReturn(null);
        when(contributionService.submitContribution(any()))
                .thenReturn(new ContributionSubmitResult(
                        "justvork",
                        "vork-central",
                        "staging",
                        "octocat",
                        "vork-central",
                        "contrib/agent/demo-core-1.0",
                        42L,
                        "https://github.com/justvork/vork-central/pull/42"));

        String logoBase64 = Base64.getEncoder().encodeToString(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47});
        ContributionController.PublishRequest request = new ContributionController.PublishRequest(
                "1.0",
                "feat: publish demo core",
                "Publish demo/core 1.0",
                "Body",
                "Summary",
                false,
                "",
                "",
                logoBase64,
                "logo.png");

        ResponseEntity<?> response = controller.publishAgent(
                "demo-core-SNAPSHOT",
                request,
                User.withUsername("alice").password("x").authorities("AGENTS_WRITE").build());

        assertEquals(200, response.getStatusCode().value());

        ArgumentCaptor<ContributionSubmitRequest> reqCaptor = ArgumentCaptor.forClass(ContributionSubmitRequest.class);
        verify(contributionService).submitContribution(reqCaptor.capture());
        assertEquals(2, reqCaptor.getValue().files().size());
        assertEquals("agents/demo/core/1.0/logo.png", reqCaptor.getValue().files().get(1).path());
        assertEquals(ContributionFile.ENCODING_BASE64, reqCaptor.getValue().files().get(1).encoding());
    }

    @Test
    void publishOAuthTemplateIncludesLogoWhenProvided() {
        OAuthTemplateEntity snapshot = new OAuthTemplateEntity(
                "oauth-template-1",
                "Google Workspace OAuth",
                "google_workspace_oauth",
                "Google OAuth defaults",
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                List.of("openid", "email"),
                Map.of("access_type", "offline"),
                sh.vork.oauth.ArtifactStatus.SNAPSHOT,
                1L,
                1L);
        when(oauthTemplateRepository.get("oauth-template-1")).thenReturn(snapshot);
        when(contributionService.submitContribution(any()))
                .thenReturn(new ContributionSubmitResult(
                        "justvork",
                        "vork-central",
                        "staging",
                        "octocat",
                        "vork-central",
                        "contrib/oauth-template/google-workspace-123",
                        99L,
                        "https://github.com/justvork/vork-central/pull/99"));

        String logoBase64 = Base64.getEncoder().encodeToString("<svg/>".getBytes());
        ContributionController.PublishMetadataRequest request = new ContributionController.PublishMetadataRequest(
                "feat: publish oauth template",
                "Publish Google Workspace OAuth template",
                "Body",
                "Add provider template",
                "- note",
                "- hint",
                logoBase64,
                "brand.svg");

        ResponseEntity<?> response = controller.publishOAuthTemplate(
                "oauth-template-1",
                request,
                User.withUsername("alice").password("x").authorities("USERS_MANAGE").build());

        assertEquals(200, response.getStatusCode().value());

        ArgumentCaptor<ContributionSubmitRequest> reqCaptor = ArgumentCaptor.forClass(ContributionSubmitRequest.class);
        verify(contributionService).submitContribution(reqCaptor.capture());
        assertEquals(2, reqCaptor.getValue().files().size());
        assertEquals("oauth-templates/google_workspace_oauth.svg", reqCaptor.getValue().files().get(1).path());
        assertEquals(ContributionFile.ENCODING_BASE64, reqCaptor.getValue().files().get(1).encoding());
    }
}
