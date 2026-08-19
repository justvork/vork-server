package sh.vork.github.contribution;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.MimeType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.ArtifactStatus;
import sh.vork.ai.AiProvider;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.oauth.OAuthTemplateEntity;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.setup.SystemSettings;
import sh.vork.setup.SystemSettingsService;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillGroup;
import sh.vork.surface.Surface;
import sh.vork.surface.service.SurfaceService;

/**
 * Contribution workflow endpoints.
 *
 * <p>Initial implementation supports Agent/Job/Surface publication to the
 * official staging repository via fork-and-PR workflow.
 */
@RestController
@RequestMapping("/api/contributions")
@PreAuthorize("isAuthenticated()")
public class ContributionController {

    private static final Logger log = LoggerFactory.getLogger(ContributionController.class);

    private static final String OFFICIAL_OWNER = "justvork";
    private static final String OFFICIAL_REPOSITORY = "vork-central";
    private static final String OFFICIAL_STAGING_BRANCH = "staging";
    private static final String DEFAULT_SURFACE_INDEX_HTML = "<!DOCTYPE html>\n<html lang=\"en\">\n<head><meta charset=\"UTF-8\"><title>Surface</title></head>\n<body></body>\n</html>\n";
    private static final String DEFAULT_SURFACE_SCRIPT_JS = "";
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[0-9]+\\.[0-9]+$");

    private final DatabaseRepository<AgentTemplate> agentRepository;
    private final DatabaseRepository<ScheduledJob> jobRepository;
    private final DatabaseRepository<Surface> surfaceRepository;
    private final DatabaseRepository<SkillGroup> skillGroupRepository;
    private final DatabaseRepository<Skill> skillRepository;
    private final DatabaseRepository<ReflectionGroup> reflectionGroupRepository;
    private final DatabaseRepository<Reflection> reflectionRepository;
    private final DatabaseRepository<ReflectionBinding> reflectionBindingRepository;
    private final DatabaseRepository<OAuthTemplateEntity> oauthTemplateRepository;
    private final DatabaseRepository<ContributionSubmission> contributionSubmissionRepository;
    private final GitHubForkContributionService contributionService;
    private final ContributionLifecyclePromotionService promotionService;
    private final ContributionVersionRecommendationService recommendationService;
    private final ContributionDependencyValidator dependencyValidator;
    private final AiOrchestrationService aiOrchestrationService;
    private final SystemSettingsService systemSettingsService;
    private final SurfaceService surfaceService;
    private final SessionFileSystem sessionFileSystem;
    private final ObjectMapper objectMapper;

    public ContributionController(DatabaseRepository<AgentTemplate> agentRepository,
                                  DatabaseRepository<ScheduledJob> jobRepository,
                                  DatabaseRepository<Surface> surfaceRepository,
                                  DatabaseRepository<SkillGroup> skillGroupRepository,
                                  DatabaseRepository<Skill> skillRepository,
                                  DatabaseRepository<ReflectionGroup> reflectionGroupRepository,
                                  DatabaseRepository<Reflection> reflectionRepository,
                                  DatabaseRepository<ReflectionBinding> reflectionBindingRepository,
                                  DatabaseRepository<OAuthTemplateEntity> oauthTemplateRepository,
                                  DatabaseRepository<ContributionSubmission> contributionSubmissionRepository,
                                  GitHubForkContributionService contributionService,
                                  ContributionLifecyclePromotionService promotionService,
                                  ContributionVersionRecommendationService recommendationService,
                                  ContributionDependencyValidator dependencyValidator,
                                  AiOrchestrationService aiOrchestrationService,
                                  SystemSettingsService systemSettingsService,
                                  SurfaceService surfaceService,
                                  SessionFileSystem sessionFileSystem,
                                  ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.jobRepository = jobRepository;
        this.surfaceRepository = surfaceRepository;
        this.skillGroupRepository = skillGroupRepository;
        this.skillRepository = skillRepository;
        this.reflectionGroupRepository = reflectionGroupRepository;
        this.reflectionRepository = reflectionRepository;
        this.reflectionBindingRepository = reflectionBindingRepository;
        this.oauthTemplateRepository = oauthTemplateRepository;
        this.contributionSubmissionRepository = contributionSubmissionRepository;
        this.contributionService = contributionService;
        this.promotionService = promotionService;
        this.recommendationService = recommendationService;
        this.dependencyValidator = dependencyValidator;
        this.aiOrchestrationService = aiOrchestrationService;
        this.systemSettingsService = systemSettingsService;
        this.surfaceService = surfaceService;
        this.sessionFileSystem = sessionFileSystem;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/agents/{id}/publish-draft")
    @PreAuthorize("hasAuthority('AGENTS_WRITE')")
    public ResponseEntity<?> draftAgentPublish(@PathVariable String id,
                                               @RequestBody(required = false) PublishDraftRequest request) {
        log.debug("ENTER draftAgentPublish: id={}", id);
        AgentTemplate existing = agentRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.systemAgent()) {
            return ResponseEntity.status(403).body(Map.of("error", "System agents cannot be published."));
        }
        if (!existing.isSnapshotMutable()) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT agents can be published."));
        }

        PublishDraft draft = buildPublishDraft(
                "agents",
                existing.uuid(),
                existing.groupId(),
                existing.artifactId(),
                "agent",
                existing,
            maybeAgentPrevious(existing.groupId(), existing.artifactId()),
            null,
            List.of());

        return ResponseEntity.ok(Map.of("status", "ok", "draft", draft));
    }

    @PostMapping("/jobs/{id}/publish-draft")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> draftJobPublish(@PathVariable String id,
                                             @AuthenticationPrincipal UserDetails user,
                                             @RequestBody(required = false) PublishDraftRequest request) {
        log.debug("ENTER draftJobPublish: id={}, user={}", id, user == null ? null : user.getUsername());
        ScheduledJob existing = jobRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (!existing.isSnapshotMutable()) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT jobs can be published."));
        }
        if (user == null || user.getUsername() == null || !user.getUsername().equals(existing.userId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        PublishDraft draft = buildPublishDraft(
                "jobs",
                existing.id(),
                existing.groupId(),
                existing.artifactId(),
                "job",
                existing,
            maybeJobPrevious(existing.groupId(), existing.artifactId()),
            null,
            List.of());

        return ResponseEntity.ok(Map.of("status", "ok", "draft", draft));
    }

    @PostMapping("/surfaces/{id}/publish-draft")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> draftSurfacePublish(@PathVariable String id,
                                                 @AuthenticationPrincipal UserDetails user,
                                                 @RequestBody(required = false) PublishDraftRequest request) {
        log.debug("ENTER draftSurfacePublish: id={}, user={}", id, user == null ? null : user.getUsername());
        Surface existing = surfaceRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (!existing.isSnapshotMutable()) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT surfaces can be published."));
        }

        String username = user == null ? null : user.getUsername();
        Surface previousSurface = maybeSurfacePrevious(existing.groupId(), existing.artifactId());
        List<Media> surfaceAssets = new ArrayList<>();
        StringBuilder assetsContext = new StringBuilder();
        appendSurfaceAssetsAsMedia(existing, username, "new", surfaceAssets, assetsContext);
        if (previousSurface != null) {
            appendSurfaceAssetsAsMedia(previousSurface, username, "previous", surfaceAssets, assetsContext);
        }

        PublishDraft draft = buildPublishDraft(
                "surfaces",
                existing.uuid(),
                existing.groupId(),
                existing.artifactId(),
                "surface",
            existing,
            previousSurface,
            assetsContext.toString(),
            surfaceAssets);

        return ResponseEntity.ok(Map.of("status", "ok", "draft", draft));
    }

    @PostMapping("/skills/{id}/publish-draft")
    @PreAuthorize("hasAuthority('SKILLS_WRITE')")
    public ResponseEntity<?> draftSkillPublish(@PathVariable String id,
                                               @RequestBody(required = false) PublishDraftRequest request) {
        log.debug("ENTER draftSkillPublish: id={}", id);
        SkillGroup existing = skillGroupRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.artifactStatus() != sh.vork.skill.ArtifactStatus.SNAPSHOT) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT skill groups can be published."));
        }

        Map<String, Object> currentArtifact = toSkillArtifactPackage(existing, skillsForGroup(existing));
        Map<String, Object> previousArtifact = maybeSkillPreviousArtifact(existing.groupId(), existing.artifactId());

        PublishDraft draft = buildPublishDraft(
                "skills",
                existing.uuid(),
                existing.groupId(),
                existing.artifactId(),
                "skill",
                currentArtifact,
                previousArtifact,
                null,
                List.of());

        return ResponseEntity.ok(Map.of("status", "ok", "draft", draft));
    }

    @PostMapping("/reflections/{id}/publish-draft")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> draftReflectionPublish(@PathVariable String id,
                                                    @RequestBody(required = false) PublishDraftRequest request) {
        log.debug("ENTER draftReflectionPublish: id={}", id);
        ReflectionGroup existing = reflectionGroupRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.artifactStatus() != sh.vork.reflection.ArtifactStatus.SNAPSHOT) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT reflection groups can be published."));
        }

        PublishDraft draft = buildPublishDraft(
                "reflections",
                existing.uuid(),
                existing.groupId(),
                existing.artifactId(),
                "reflection group",
                toReflectionArtifactPackage(existing,
                        reflectionsForGroup(existing.uuid()),
                        bindingsForGroup(existing.uuid())),
                maybeReflectionPreviousArtifact(existing.groupId(), existing.artifactId()),
                null,
                List.of());

        return ResponseEntity.ok(Map.of("status", "ok", "draft", draft));
    }

    @PostMapping("/oauth-templates/{id}/publish-draft")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> draftOAuthTemplatePublish(@PathVariable String id,
                                                       @RequestBody(required = false) PublishDraftRequest request) {
        log.debug("ENTER draftOAuthTemplatePublish: id={}", id);
        OAuthTemplateEntity existing = oauthTemplateRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.artifactStatus() != sh.vork.oauth.ArtifactStatus.SNAPSHOT
                && existing.artifactStatus() != sh.vork.oauth.ArtifactStatus.REJECTED) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT or REJECTED OAuth templates can be published."));
        }
        if (existing.clientName() == null || existing.clientName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "OAuth template is missing clientName."));
        }

        PublishMetadataDraft draft = buildOAuthTemplatePublishDraft(existing);
        return ResponseEntity.ok(Map.of("status", "ok", "draft", draft));
    }

    @PostMapping("/agents/{id}/publish")
    @PreAuthorize("hasAuthority('AGENTS_WRITE')")
    public ResponseEntity<?> publishAgent(@PathVariable String id,
                                          @RequestBody PublishRequest request,
                                          @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER publishAgent: id={}, user={}", id, user == null ? null : user.getUsername());
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        AgentTemplate existing = agentRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.systemAgent()) {
            return ResponseEntity.status(403).body(Map.of("error", "System agents cannot be published."));
        }
        if (!existing.isSnapshotMutable()) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT agents can be published."));
        }

        ResponseEntity<?> dependencyFailure = dependencyFailureIfAny(
                dependencyValidator.validateAgent(existing.uuid()));
        if (dependencyFailure != null) {
            return dependencyFailure;
        }

        String validationError = validatePublishRequest(request, existing.groupId(), existing.artifactId(), "Agent");
        if (validationError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", validationError));
        }

        String nextVersion = request.version().trim();
        String nextUuid = toVid(existing.groupId(), existing.artifactId(), nextVersion);
        if (!nextUuid.equals(existing.uuid()) && agentRepository.get(nextUuid) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "Target artifact version already exists locally: " + nextUuid));
        }

        AgentTemplate publishedSnapshot = new AgentTemplate(
                nextUuid,
                existing.name(),
                existing.systemPrompt(),
                existing.allowedTools(),
                false,
                existing.skillUuids(),
                existing.agentType(),
                existing.bindingUuids(),
                existing.assignedUsernames(),
                existing.jobUuids(),
                existing.recommendedModel(),
                existing.groupId(),
                existing.artifactId(),
                nextVersion,
                ArtifactStatus.SUBMITTED);

        String agentJson;
        try {
            agentJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(publishedSnapshot);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize agent for contribution [id={}, targetVersion={}]: {}",
                    id, nextVersion, ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to serialize contribution artifact."));
        }

        String branch = buildBranchName("agent", existing.groupId(), existing.artifactId(), nextVersion);
        String commitMessage = request.commitMessage() == null || request.commitMessage().isBlank()
            ? defaultCommitMessage("agent",
            describeArtifactHeadline("agent", existing.uuid(), existing),
            nextVersion,
            request.changeSummary(),
            isFirstReleaseVersion(nextVersion))
                : request.commitMessage().trim();
        String prBody = request.prBody() == null ? "" : request.prBody().trim();

        String artifactBasePath = "agents/" + existing.groupId() + "/" + existing.artifactId() + "/" + nextVersion;
        List<ContributionFile> files = new ArrayList<>();
        files.add(new ContributionFile(
            artifactBasePath + "/agent.json",
            agentJson));
        appendArtifactLogoIfPresent(files, request.logoBase64(), request.logoFileName(), artifactBasePath);

        ContributionSubmitRequest submitRequest = new ContributionSubmitRequest(
                user.getUsername(),
                branch,
                commitMessage,
                request.prTitle().trim(),
                prBody,
                new ContributionTarget(OFFICIAL_OWNER, OFFICIAL_REPOSITORY, OFFICIAL_STAGING_BRANCH),
            files);

        ContributionSubmitResult result;
        try {
            result = contributionService.submitContribution(submitRequest);
        } catch (RuntimeException ex) {
            log.warn("Agent contribution publish failed [id={}, branch={}]: {}", id, branch, ex.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "Contribution submission failed: " + ex.getMessage()));
        }

        agentRepository.save(publishedSnapshot);
        contributionSubmissionRepository.save(new ContributionSubmission(
            submissionKey("agent", publishedSnapshot.uuid()),
            "agent",
            publishedSnapshot.uuid(),
            result.upstreamOwner(),
            result.upstreamRepository(),
            result.baseBranch(),
            result.branchName(),
            result.pullRequestNumber(),
            result.pullRequestUrl(),
            System.currentTimeMillis(),
            System.currentTimeMillis()));
        if (!nextUuid.equals(existing.uuid())) {
            agentRepository.delete(existing.uuid());
        }

        log.info("Agent submitted [oldId={}, newId={}, pr={}]", existing.uuid(), nextUuid, result.pullRequestUrl());
        log.debug("EXIT publishAgent: id={}, targetVersion={}", id, nextVersion);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "artifact", publishedSnapshot,
                "pullRequest", Map.of(
                        "number", result.pullRequestNumber(),
                        "url", result.pullRequestUrl(),
                        "branch", result.branchName(),
                        "forkOwner", result.forkOwner(),
                        "forkRepository", result.forkRepository())));
    }

    @PostMapping("/jobs/{id}/publish")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> publishJob(@PathVariable String id,
                                        @RequestBody PublishRequest request,
                                        @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER publishJob: id={}, user={}", id, user == null ? null : user.getUsername());
        ScheduledJob existing = jobRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (!existing.isSnapshotMutable()) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT jobs can be published."));
        }
        if (user == null || user.getUsername() == null || !user.getUsername().equals(existing.userId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }

        ResponseEntity<?> dependencyFailure = dependencyFailureIfAny(
                dependencyValidator.validateJob(existing.id()));
        if (dependencyFailure != null) {
            return dependencyFailure;
        }

        String validationError = validatePublishRequest(request, existing.groupId(), existing.artifactId(), "Job");
        if (validationError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", validationError));
        }

        String nextVersion = request.version().trim();
        String nextUuid = toVid(existing.groupId(), existing.artifactId(), nextVersion);
        if (!nextUuid.equals(existing.id()) && jobRepository.get(nextUuid) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "Target artifact version already exists locally: " + nextUuid));
        }

        ScheduledJob submitted = new ScheduledJob(
                nextUuid,
                existing.name(),
                existing.aiPrompt(),
                existing.sessionUuid(),
                existing.userId(),
                existing.invocationType(),
                existing.startTime(),
                existing.repeatDuration(),
                existing.durationType(),
                existing.lastExecutionTime(),
                existing.nextExecutionTime(),
                existing.agentTemplateId(),
                existing.provider(),
                existing.modelId(),
                existing.oobTimeoutMinutes(),
                existing.expectedOutput(),
                existing.status(),
                existing.skillUuids(),
                existing.toolIds(),
                existing.notificationUserIds(),
                existing.groupId(),
                existing.artifactId(),
                nextVersion,
                sh.vork.scheduling.domain.ArtifactStatus.SUBMITTED);

        String jobJson;
        try {
            jobJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(submitted);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize job for contribution [id={}, targetVersion={}]: {}",
                    id, nextVersion, ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to serialize contribution artifact."));
        }

        String branch = buildBranchName("job", existing.groupId(), existing.artifactId(), nextVersion);
        String commitMessage = request.commitMessage() == null || request.commitMessage().isBlank()
            ? defaultCommitMessage("job",
            describeArtifactHeadline("job", existing.id(), existing),
            nextVersion,
            request.changeSummary(),
            isFirstReleaseVersion(nextVersion))
                : request.commitMessage().trim();
        String prBody = request.prBody() == null ? "" : request.prBody().trim();

        String artifactBasePath = "jobs/" + existing.groupId() + "/" + existing.artifactId() + "/" + nextVersion;
        List<ContributionFile> files = new ArrayList<>();
        files.add(new ContributionFile(
            artifactBasePath + "/job.json",
            jobJson));
        appendArtifactLogoIfPresent(files, request.logoBase64(), request.logoFileName(), artifactBasePath);

        ContributionSubmitRequest submitRequest = new ContributionSubmitRequest(
                user.getUsername(),
                branch,
                commitMessage,
                request.prTitle().trim(),
                prBody,
                new ContributionTarget(OFFICIAL_OWNER, OFFICIAL_REPOSITORY, OFFICIAL_STAGING_BRANCH),
            files);

        ContributionSubmitResult result;
        try {
            result = contributionService.submitContribution(submitRequest);
        } catch (RuntimeException ex) {
            log.warn("Job contribution publish failed [id={}, branch={}]: {}", id, branch, ex.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "Contribution submission failed: " + ex.getMessage()));
        }

        jobRepository.save(submitted);
        contributionSubmissionRepository.save(new ContributionSubmission(
            submissionKey("job", submitted.id()),
            "job",
            submitted.id(),
            result.upstreamOwner(),
            result.upstreamRepository(),
            result.baseBranch(),
            result.branchName(),
            result.pullRequestNumber(),
            result.pullRequestUrl(),
            System.currentTimeMillis(),
            System.currentTimeMillis()));
        if (!nextUuid.equals(existing.id())) {
            jobRepository.delete(existing.id());
        }

        log.info("Job submitted [oldId={}, newId={}, pr={}]", existing.id(), nextUuid, result.pullRequestUrl());
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "artifact", submitted,
                "pullRequest", Map.of(
                        "number", result.pullRequestNumber(),
                        "url", result.pullRequestUrl(),
                        "branch", result.branchName(),
                        "forkOwner", result.forkOwner(),
                        "forkRepository", result.forkRepository())));
    }

    @PostMapping("/surfaces/{id}/publish")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> publishSurface(@PathVariable String id,
                                            @RequestBody PublishRequest request,
                                            @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER publishSurface: id={}, user={}", id, user == null ? null : user.getUsername());
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        Surface existing = surfaceRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (!existing.isSnapshotMutable()) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT surfaces can be published."));
        }

        ResponseEntity<?> dependencyFailure = dependencyFailureIfAny(
                dependencyValidator.validateSurface(existing.uuid()));
        if (dependencyFailure != null) {
            return dependencyFailure;
        }

        String validationError = validatePublishRequest(request, existing.groupId(), existing.artifactId(), "Surface");
        if (validationError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", validationError));
        }

        String nextVersion = request.version().trim();
        String nextUuid = toVid(existing.groupId(), existing.artifactId(), nextVersion);
        if (!nextUuid.equals(existing.uuid()) && surfaceRepository.get(nextUuid) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "Target artifact version already exists locally: " + nextUuid));
        }

        Surface submitted = new Surface(
                nextUuid,
                existing.toolId(),
                existing.name(),
                existing.description(),
                existing.sessionUuid(),
                existing.executionSessionUuid(),
                existing.skillUuids(),
                existing.reflectionBindingUuids(),
                existing.jobUuids(),
                existing.groupId(),
                existing.artifactId(),
                nextVersion,
                sh.vork.surface.ArtifactStatus.SUBMITTED,
                existing.createdAt(),
                System.currentTimeMillis());

        String surfaceJson;
        try {
            Map<String, Object> surfaceArtifact = new LinkedHashMap<>();
            surfaceArtifact.put("uuid", submitted.uuid());
            surfaceArtifact.put("name", submitted.name());
            surfaceArtifact.put("description", submitted.description());
            surfaceArtifact.put("skillUuids", submitted.skillUuids());
            surfaceArtifact.put("reflectionBindingUuids", submitted.reflectionBindingUuids());
            surfaceArtifact.put("jobUuids", submitted.jobUuids());
            surfaceArtifact.put("groupId", submitted.groupId());
            surfaceArtifact.put("artifactId", submitted.artifactId());
            surfaceArtifact.put("version", submitted.version());
            surfaceArtifact.put("artifactStatus", submitted.artifactStatus());
            surfaceJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(surfaceArtifact);
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize surface for contribution [id={}, targetVersion={}]: {}",
                    id, nextVersion, ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to serialize contribution artifact."));
        }

        String indexHtml = readSurfaceAsset(existing, user == null ? null : user.getUsername(), "index.html", DEFAULT_SURFACE_INDEX_HTML);
        String scriptJs = readSurfaceAsset(existing, user == null ? null : user.getUsername(), "script.js", DEFAULT_SURFACE_SCRIPT_JS);

        String branch = buildBranchName("surface", existing.groupId(), existing.artifactId(), nextVersion);
        String commitMessage = request.commitMessage() == null || request.commitMessage().isBlank()
            ? defaultCommitMessage("surface",
            describeArtifactHeadline("surface", existing.uuid(), existing),
            nextVersion,
            request.changeSummary(),
            isFirstReleaseVersion(nextVersion))
                : request.commitMessage().trim();
        String prBody = request.prBody() == null ? "" : request.prBody().trim();

        String artifactBasePath = "surfaces/" + existing.groupId() + "/" + existing.artifactId() + "/" + nextVersion;
        List<ContributionFile> files = new ArrayList<>();
        files.add(new ContributionFile(
            artifactBasePath + "/surface.json",
            surfaceJson));
        files.add(new ContributionFile(
            artifactBasePath + "/assets/index.html",
            indexHtml));
        files.add(new ContributionFile(
            artifactBasePath + "/assets/script.js",
            scriptJs));
        appendArtifactLogoIfPresent(files, request.logoBase64(), request.logoFileName(), artifactBasePath);

        ContributionSubmitRequest submitRequest = new ContributionSubmitRequest(
                user.getUsername(),
                branch,
                commitMessage,
                request.prTitle().trim(),
                prBody,
                new ContributionTarget(OFFICIAL_OWNER, OFFICIAL_REPOSITORY, OFFICIAL_STAGING_BRANCH),
            files);

        ContributionSubmitResult result;
        try {
            result = contributionService.submitContribution(submitRequest);
        } catch (RuntimeException ex) {
            log.warn("Surface contribution publish failed [id={}, branch={}]: {}", id, branch, ex.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "Contribution submission failed: " + ex.getMessage()));
        }

        surfaceRepository.save(submitted);
        contributionSubmissionRepository.save(new ContributionSubmission(
            submissionKey("surface", submitted.uuid()),
            "surface",
            submitted.uuid(),
            result.upstreamOwner(),
            result.upstreamRepository(),
            result.baseBranch(),
            result.branchName(),
            result.pullRequestNumber(),
            result.pullRequestUrl(),
            System.currentTimeMillis(),
            System.currentTimeMillis()));
        if (!nextUuid.equals(existing.uuid())) {
            surfaceRepository.delete(existing.uuid());
        }

        log.info("Surface submitted [oldId={}, newId={}, pr={}]", existing.uuid(), nextUuid, result.pullRequestUrl());
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "artifact", submitted,
                "pullRequest", Map.of(
                        "number", result.pullRequestNumber(),
                        "url", result.pullRequestUrl(),
                        "branch", result.branchName(),
                        "forkOwner", result.forkOwner(),
                        "forkRepository", result.forkRepository())));
    }

    @PostMapping("/skills/{id}/publish")
    @PreAuthorize("hasAuthority('SKILLS_WRITE')")
    public ResponseEntity<?> publishSkill(@PathVariable String id,
                                          @RequestBody PublishRequest request,
                                          @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER publishSkill: id={}, user={}", id, user == null ? null : user.getUsername());
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        SkillGroup existing = skillGroupRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.artifactStatus() != sh.vork.skill.ArtifactStatus.SNAPSHOT) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT skill groups can be published."));
        }

        ResponseEntity<?> dependencyFailure = dependencyFailureIfAny(
                dependencyValidator.validateSkillGroup(existing.uuid()));
        if (dependencyFailure != null) {
            return dependencyFailure;
        }

        String validationError = validatePublishRequest(request, existing.groupId(), existing.artifactId(), "Skill group");
        if (validationError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", validationError));
        }

        String nextVersion = request.version().trim();
        String nextUuid = toVid(existing.groupId(), existing.artifactId(), nextVersion);
        if (!nextUuid.equals(existing.uuid()) && skillGroupRepository.get(nextUuid) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "Target artifact version already exists locally: " + nextUuid));
        }

        List<Skill> currentSkills = skillsForGroup(existing);
        List<Skill> relocatedSkills = currentSkills.stream()
                .map(skill -> cloneSkillForGroup(skill, nextUuid, skill.uuid(), System.currentTimeMillis(), null))
                .toList();
        SkillGroup submitted = new SkillGroup(
                nextUuid,
                existing.name(),
                existing.author(),
                existing.category(),
                relocatedSkills,
                existing.groupId(),
                existing.artifactId(),
                nextVersion,
                sh.vork.skill.ArtifactStatus.SUBMITTED,
                existing.createdAt(),
                System.currentTimeMillis());

        String skillsJson;
        try {
            skillsJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(toSkillArtifactPackage(submitted, relocatedSkills));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize skill-group for contribution [id={}, targetVersion={}]: {}",
                    id, nextVersion, ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to serialize contribution artifact."));
        }

        String branch = buildBranchName("skill", existing.groupId(), existing.artifactId(), nextVersion);
        String commitMessage = request.commitMessage() == null || request.commitMessage().isBlank()
                ? defaultCommitMessage("skill",
                describeArtifactHeadline("skill", existing.uuid(), existing),
                nextVersion,
                request.changeSummary(),
                isFirstReleaseVersion(nextVersion))
                : request.commitMessage().trim();
        String prBody = request.prBody() == null ? "" : request.prBody().trim();

        String artifactBasePath = "skills/" + existing.groupId() + "/" + existing.artifactId() + "/" + nextVersion;
        List<ContributionFile> files = new ArrayList<>();
        files.add(new ContributionFile(
            artifactBasePath + "/skills.json",
            skillsJson));
        appendArtifactLogoIfPresent(files, request.logoBase64(), request.logoFileName(), artifactBasePath);

        ContributionSubmitRequest submitRequest = new ContributionSubmitRequest(
                user.getUsername(),
                branch,
                commitMessage,
                request.prTitle().trim(),
                prBody,
                new ContributionTarget(OFFICIAL_OWNER, OFFICIAL_REPOSITORY, OFFICIAL_STAGING_BRANCH),
            files);

        ContributionSubmitResult result;
        try {
            result = contributionService.submitContribution(submitRequest);
        } catch (RuntimeException ex) {
            log.warn("Skill-group contribution publish failed [id={}, branch={}]: {}", id, branch, ex.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "Contribution submission failed: " + ex.getMessage()));
        }

        relocatedSkills.forEach(skillRepository::save);
        skillGroupRepository.save(submitted);
        contributionSubmissionRepository.save(new ContributionSubmission(
                submissionKey("skill", submitted.uuid()),
                "skill",
                submitted.uuid(),
                result.upstreamOwner(),
                result.upstreamRepository(),
                result.baseBranch(),
                result.branchName(),
                result.pullRequestNumber(),
                result.pullRequestUrl(),
                System.currentTimeMillis(),
                System.currentTimeMillis()));
        if (!nextUuid.equals(existing.uuid())) {
            skillGroupRepository.delete(existing.uuid());
        }

        log.info("Skill-group submitted [oldId={}, newId={}, pr={}]", existing.uuid(), nextUuid, result.pullRequestUrl());
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "artifact", submitted,
                "pullRequest", Map.of(
                        "number", result.pullRequestNumber(),
                        "url", result.pullRequestUrl(),
                        "branch", result.branchName(),
                        "forkOwner", result.forkOwner(),
                        "forkRepository", result.forkRepository())));
    }

    @PostMapping("/reflections/{id}/publish")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> publishReflection(@PathVariable String id,
                                               @RequestBody PublishRequest request,
                                               @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER publishReflection: id={}, user={}", id, user == null ? null : user.getUsername());
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        ReflectionGroup existing = reflectionGroupRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.artifactStatus() != sh.vork.reflection.ArtifactStatus.SNAPSHOT) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT reflection groups can be published."));
        }

        ResponseEntity<?> dependencyFailure = dependencyFailureIfAny(
                dependencyValidator.validateReflectionGroup(existing.uuid()));
        if (dependencyFailure != null) {
            return dependencyFailure;
        }

        String validationError = validatePublishRequest(request, existing.groupId(), existing.artifactId(), "Reflection group");
        if (validationError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", validationError));
        }

        String nextVersion = request.version().trim();
        String nextUuid = toVid(existing.groupId(), existing.artifactId(), nextVersion);
        if (!nextUuid.equals(existing.uuid()) && reflectionGroupRepository.get(nextUuid) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "Target artifact version already exists locally: " + nextUuid));
        }

        long now = System.currentTimeMillis();
        List<Reflection> sourceReflections = reflectionsForGroup(existing.uuid());
        List<ReflectionBinding> sourceBindings = bindingsForGroup(existing.uuid());

        List<Reflection> relocatedReflections = sourceReflections.stream()
                .map(reflection -> cloneReflectionForGroup(reflection, nextUuid, reflection.uuid(), now))
                .toList();
        List<ReflectionBinding> relocatedBindings = sourceBindings.stream()
                .map(binding -> cloneBindingForGroup(binding, nextUuid, binding.uuid(), now))
                .toList();

        ReflectionGroup submitted = new ReflectionGroup(
                nextUuid,
                existing.toolId(),
                existing.name(),
                existing.description(),
                existing.type(),
                existing.baseUrl(),
                existing.urlOverrideEnabled(),
                existing.bindingSecrets(),
                existing.bindingParameters(),
                existing.authenticationMode(),
                existing.oauthTemplateId(),
                existing.groupId(),
                existing.artifactId(),
                nextVersion,
                sh.vork.reflection.ArtifactStatus.SUBMITTED,
                existing.createdAt(),
                now);

        String reflectionsJson;
        try {
            reflectionsJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(toReflectionArtifactPackage(submitted, relocatedReflections, relocatedBindings));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize reflection-group for contribution [id={}, targetVersion={}]: {}",
                    id, nextVersion, ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to serialize contribution artifact."));
        }

        String branch = buildBranchName("reflection", existing.groupId(), existing.artifactId(), nextVersion);
        String commitMessage = request.commitMessage() == null || request.commitMessage().isBlank()
                ? defaultCommitMessage("reflection",
                describeArtifactHeadline("reflection", existing.uuid(), submitted),
                nextVersion,
                request.changeSummary(),
                isFirstReleaseVersion(nextVersion))
                : request.commitMessage().trim();
        String prBody = request.prBody() == null ? "" : request.prBody().trim();

        String artifactBasePath = "reflections/" + existing.groupId() + "/" + existing.artifactId() + "/" + nextVersion;
        List<ContributionFile> files = new ArrayList<>();
        files.add(new ContributionFile(
            artifactBasePath + "/reflections.json",
            reflectionsJson));
        appendArtifactLogoIfPresent(files, request.logoBase64(), request.logoFileName(), artifactBasePath);

        ContributionSubmitRequest submitRequest = new ContributionSubmitRequest(
                user.getUsername(),
                branch,
                commitMessage,
                request.prTitle().trim(),
                prBody,
                new ContributionTarget(OFFICIAL_OWNER, OFFICIAL_REPOSITORY, OFFICIAL_STAGING_BRANCH),
            files);

        ContributionSubmitResult result;
        try {
            result = contributionService.submitContribution(submitRequest);
        } catch (RuntimeException ex) {
            log.warn("Reflection-group contribution publish failed [id={}, branch={}]: {}", id, branch, ex.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "Contribution submission failed: " + ex.getMessage()));
        }

        relocatedReflections.forEach(reflectionRepository::save);
        relocatedBindings.forEach(reflectionBindingRepository::save);
        reflectionGroupRepository.save(submitted);
        contributionSubmissionRepository.save(new ContributionSubmission(
                submissionKey("reflection", submitted.uuid()),
                "reflection",
                submitted.uuid(),
                result.upstreamOwner(),
                result.upstreamRepository(),
                result.baseBranch(),
                result.branchName(),
                result.pullRequestNumber(),
                result.pullRequestUrl(),
                System.currentTimeMillis(),
                System.currentTimeMillis()));
        if (!nextUuid.equals(existing.uuid())) {
            reflectionGroupRepository.delete(existing.uuid());
        }

        log.info("Reflection-group submitted [oldId={}, newId={}, pr={}]", existing.uuid(), nextUuid, result.pullRequestUrl());
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "artifact", submitted,
                "pullRequest", Map.of(
                        "number", result.pullRequestNumber(),
                        "url", result.pullRequestUrl(),
                        "branch", result.branchName(),
                        "forkOwner", result.forkOwner(),
                        "forkRepository", result.forkRepository())));
    }

    @PostMapping("/oauth-templates/{id}/publish")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> publishOAuthTemplate(@PathVariable String id,
                                                  @RequestBody PublishMetadataRequest request,
                                                  @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER publishOAuthTemplate: id={}, user={}", id, user == null ? null : user.getUsername());
        if (user == null || user.getUsername() == null || user.getUsername().isBlank()) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        OAuthTemplateEntity existing = oauthTemplateRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.artifactStatus() != sh.vork.oauth.ArtifactStatus.SNAPSHOT
                && existing.artifactStatus() != sh.vork.oauth.ArtifactStatus.REJECTED) {
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT or REJECTED OAuth templates can be published."));
        }
        if (existing.clientName() == null || existing.clientName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "OAuth template is missing clientName."));
        }

        String validationError = validateOAuthTemplatePublishRequest(request, existing);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", validationError));
        }

        String templateJson;
        try {
            templateJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(toOAuthTemplateContributionArtifact(existing));
        } catch (JsonProcessingException ex) {
            log.error("Failed to serialize oauth-template for contribution [id={}]: {}",
                    id, ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to serialize contribution artifact."));
        }

        String clientName = existing.clientName().trim();
        String branch = buildBranchNameNoVersion("oauth-template", clientName);
        String commitMessage = request.commitMessage() == null || request.commitMessage().isBlank()
                ? "contrib(oauth-template): publish \"" + clientName + "\""
                : request.commitMessage().trim();
        String prBody = request.prBody() == null ? "" : request.prBody().trim();

        List<ContributionFile> files = new ArrayList<>();
        files.add(new ContributionFile(
            "oauth-templates/" + clientName + ".json",
            templateJson));
        appendNamedLogoIfPresent(files, request.logoBase64(), request.logoFileName(), "oauth-templates/" + clientName);

        ContributionSubmitRequest submitRequest = new ContributionSubmitRequest(
                user.getUsername(),
                branch,
                commitMessage,
                request.prTitle().trim(),
                prBody,
                new ContributionTarget(OFFICIAL_OWNER, OFFICIAL_REPOSITORY, OFFICIAL_STAGING_BRANCH),
            files);

        ContributionSubmitResult result;
        try {
            result = contributionService.submitContribution(submitRequest);
        } catch (RuntimeException ex) {
            log.warn("OAuth-template contribution publish failed [id={}, branch={}]: {}", id, branch, ex.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "Contribution submission failed: " + ex.getMessage()));
        }

        OAuthTemplateEntity submitted = new OAuthTemplateEntity(
                existing.uuid(),
                existing.name(),
                existing.clientName(),
                existing.description(),
                existing.authorizeEndpoint(),
                existing.tokenEndpoint(),
                existing.scopes(),
                existing.authorizationParameters(),
                sh.vork.oauth.ArtifactStatus.SUBMITTED,
                existing.createdAt(),
                System.currentTimeMillis());
        oauthTemplateRepository.save(submitted);
        contributionSubmissionRepository.save(new ContributionSubmission(
                submissionKey("oauth-template", submitted.uuid()),
                "oauth-template",
                submitted.uuid(),
                result.upstreamOwner(),
                result.upstreamRepository(),
                result.baseBranch(),
                result.branchName(),
                result.pullRequestNumber(),
                result.pullRequestUrl(),
                System.currentTimeMillis(),
                System.currentTimeMillis()));

        log.info("OAuth-template submitted [id={}, clientName={}, pr={}]", submitted.uuid(), clientName, result.pullRequestUrl());
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "artifact", submitted,
                "pullRequest", Map.of(
                        "number", result.pullRequestNumber(),
                        "url", result.pullRequestUrl(),
                        "branch", result.branchName(),
                        "forkOwner", result.forkOwner(),
                        "forkRepository", result.forkRepository())));
    }

    @GetMapping("/{componentType}/{id}/dependency-check")
    public ResponseEntity<?> dependencyCheck(@PathVariable String componentType,
                                             @PathVariable String id) {
        log.debug("ENTER dependencyCheck: componentType={}, id={}", componentType, id);
        if (componentType == null || componentType.isBlank() || id == null || id.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "componentType and id are required."));
        }

        ContributionDependencyValidator.DependencyValidationReport report;
        switch (componentType.trim().toLowerCase()) {
            case "agents" -> report = dependencyValidator.validateAgent(id);
            case "jobs" -> report = dependencyValidator.validateJob(id);
            case "surfaces" -> report = dependencyValidator.validateSurface(id);
            case "skills" -> report = dependencyValidator.validateSkillGroup(id);
            case "reflections" -> report = dependencyValidator.validateReflectionGroup(id);
            default -> {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Unsupported componentType for dependency check: " + componentType,
                        "supported", List.of("agents", "jobs", "surfaces", "skills", "reflections")));
            }
        }

        log.debug("EXIT dependencyCheck: componentType={}, id={}, valid={}, issues={}",
                componentType, id, report.valid(), report.issues().size());
        return ResponseEntity.ok(Map.of(
                "status", report.valid() ? "ok" : "blocked",
                "report", report));
    }

    @PostMapping("/agents/{id}/recommend-version")
    @PreAuthorize("hasAuthority('AGENTS_WRITE')")
    public ResponseEntity<?> recommendAgentVersion(@PathVariable String id,
                                                   @RequestBody(required = false) VersionRecommendationRequest request) {
        log.debug("ENTER recommendAgentVersion: id={}", id);
        AgentTemplate existing = agentRepository.get(id);
        if (existing == null) {
            log.warn("Agent recommendation target not found [id={}]", id);
            return ResponseEntity.notFound().build();
        }
        if (existing.systemAgent()) {
            log.warn("Agent recommendation blocked for system agent [id={}]", id);
            return ResponseEntity.status(403).body(Map.of("error", "System agents are not versioned artifacts."));
        }
        if (existing.groupId() == null || existing.artifactId() == null) {
            log.warn("Agent recommendation missing metadata [id={}]", id);
            return ResponseEntity.badRequest().body(Map.of("error", "Agent is missing contribution identity metadata (groupId/artifactId)."));
        }

        boolean breakingChange = request != null && request.breakingChange();
        try {
            ContributionVersionRecommendationService.Recommendation recommendation =
                    recommendationService.recommendNextVersion("agents", existing.groupId(), existing.artifactId(), breakingChange);
            log.debug("EXIT recommendAgentVersion: id={}, latestVersion={}, recommendedVersion={}",
                    id, recommendation.latestVersion(), recommendation.recommendedVersion());
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "recommendation", recommendation));
        } catch (RuntimeException ex) {
            log.warn("Agent recommendation failed [id={}, groupId={}, artifactId={}]: {}",
                    id, existing.groupId(), existing.artifactId(), ex.getMessage(), ex);
            return ResponseEntity.status(502).body(Map.of("error", "Recommendation failed: " + ex.getMessage()));
        }
    }

    @GetMapping("/agents/{id}/recommend-version")
    @PreAuthorize("hasAuthority('AGENTS_WRITE')")
    public ResponseEntity<?> recommendAgentVersionGet(@PathVariable String id,
                                                      @RequestParam(name = "breakingChange", required = false, defaultValue = "false") boolean breakingChange,
                                                      @RequestParam(name = "changeSummary", required = false) String changeSummary) {
        log.debug("ENTER recommendAgentVersionGet: id={}, breakingChange={}", id, breakingChange);
        return recommendAgentVersion(id, new VersionRecommendationRequest(breakingChange, changeSummary));
    }

    @PostMapping("/jobs/{id}/recommend-version")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> recommendJobVersion(@PathVariable String id,
                                                 @AuthenticationPrincipal UserDetails user,
                                                 @RequestBody(required = false) VersionRecommendationRequest request) {
        log.debug("ENTER recommendJobVersion: id={}, user={}", id, user == null ? null : user.getUsername());
        ScheduledJob existing = jobRepository.get(id);
        if (existing == null) {
            log.warn("Job recommendation target not found [id={}]", id);
            return ResponseEntity.notFound().build();
        }
        if (user == null || user.getUsername() == null || !user.getUsername().equals(existing.userId())) {
            log.warn("Job recommendation forbidden [id={}, user={}, owner={}]",
                id, user == null ? null : user.getUsername(), existing.userId());
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (existing.groupId() == null || existing.artifactId() == null) {
            log.warn("Job recommendation missing metadata [id={}]", id);
            return ResponseEntity.badRequest().body(Map.of("error", "Job is missing contribution identity metadata (groupId/artifactId)."));
        }

        boolean breakingChange = request != null && request.breakingChange();
        try {
            ContributionVersionRecommendationService.Recommendation recommendation =
                recommendationService.recommendNextVersion("jobs", existing.groupId(), existing.artifactId(), breakingChange);
            log.debug("EXIT recommendJobVersion: id={}, latestVersion={}, recommendedVersion={}",
                id, recommendation.latestVersion(), recommendation.recommendedVersion());
            return ResponseEntity.ok(Map.of(
                "status", "ok",
                "recommendation", recommendation));
        } catch (RuntimeException ex) {
            log.warn("Job recommendation failed [id={}, groupId={}, artifactId={}]: {}",
                id, existing.groupId(), existing.artifactId(), ex.getMessage(), ex);
            return ResponseEntity.status(502).body(Map.of("error", "Recommendation failed: " + ex.getMessage()));
        }
    }

    @GetMapping("/jobs/{id}/recommend-version")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> recommendJobVersionGet(@PathVariable String id,
                                                    @AuthenticationPrincipal UserDetails user,
                                                    @RequestParam(name = "breakingChange", required = false, defaultValue = "false") boolean breakingChange,
                                                    @RequestParam(name = "changeSummary", required = false) String changeSummary) {
        log.debug("ENTER recommendJobVersionGet: id={}, user={}, breakingChange={}",
            id, user == null ? null : user.getUsername(), breakingChange);
        return recommendJobVersion(id, user, new VersionRecommendationRequest(breakingChange, changeSummary));
    }

    @PostMapping("/surfaces/{id}/recommend-version")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> recommendSurfaceVersion(@PathVariable String id,
                                                     @RequestBody(required = false) VersionRecommendationRequest request) {
        log.debug("ENTER recommendSurfaceVersion: id={}", id);
        Surface existing = surfaceRepository.get(id);
        if (existing == null) {
            log.warn("Surface recommendation target not found [id={}]", id);
            return ResponseEntity.notFound().build();
        }
        if (existing.groupId() == null || existing.artifactId() == null) {
            log.warn("Surface recommendation missing metadata [id={}]", id);
            return ResponseEntity.badRequest().body(Map.of("error", "Surface is missing contribution identity metadata (groupId/artifactId)."));
        }

        boolean breakingChange = request != null && request.breakingChange();
        try {
            ContributionVersionRecommendationService.Recommendation recommendation =
                recommendationService.recommendNextVersion("surfaces", existing.groupId(), existing.artifactId(), breakingChange);
            log.debug("EXIT recommendSurfaceVersion: id={}, latestVersion={}, recommendedVersion={}",
                id, recommendation.latestVersion(), recommendation.recommendedVersion());
            return ResponseEntity.ok(Map.of(
                "status", "ok",
                "recommendation", recommendation));
        } catch (RuntimeException ex) {
            log.warn("Surface recommendation failed [id={}, groupId={}, artifactId={}]: {}",
                id, existing.groupId(), existing.artifactId(), ex.getMessage(), ex);
            return ResponseEntity.status(502).body(Map.of("error", "Recommendation failed: " + ex.getMessage()));
        }
    }

    @PostMapping("/skills/{id}/recommend-version")
    @PreAuthorize("hasAuthority('SKILLS_WRITE')")
    public ResponseEntity<?> recommendSkillVersion(@PathVariable String id,
                                                   @RequestBody(required = false) VersionRecommendationRequest request) {
        log.debug("ENTER recommendSkillVersion: id={}", id);
        SkillGroup existing = skillGroupRepository.get(id);
        if (existing == null) {
            log.warn("Skill-group recommendation target not found [id={}]", id);
            return ResponseEntity.notFound().build();
        }
        if (existing.groupId() == null || existing.artifactId() == null) {
            log.warn("Skill-group recommendation missing metadata [id={}]", id);
            return ResponseEntity.badRequest().body(Map.of("error", "Skill group is missing contribution identity metadata (groupId/artifactId)."));
        }

        boolean breakingChange = request != null && request.breakingChange();
        try {
            ContributionVersionRecommendationService.Recommendation recommendation =
                    recommendationService.recommendNextVersion("skills", existing.groupId(), existing.artifactId(), breakingChange);
            log.debug("EXIT recommendSkillVersion: id={}, latestVersion={}, recommendedVersion={}",
                    id, recommendation.latestVersion(), recommendation.recommendedVersion());
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "recommendation", recommendation));
        } catch (RuntimeException ex) {
            log.warn("Skill-group recommendation failed [id={}, groupId={}, artifactId={}]: {}",
                    id, existing.groupId(), existing.artifactId(), ex.getMessage(), ex);
            return ResponseEntity.status(502).body(Map.of("error", "Recommendation failed: " + ex.getMessage()));
        }
    }

    @PostMapping("/reflections/{id}/recommend-version")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> recommendReflectionVersion(@PathVariable String id,
                                                        @RequestBody(required = false) VersionRecommendationRequest request) {
        log.debug("ENTER recommendReflectionVersion: id={}", id);
        ReflectionGroup existing = reflectionGroupRepository.get(id);
        if (existing == null) {
            log.warn("Reflection-group recommendation target not found [id={}]", id);
            return ResponseEntity.notFound().build();
        }
        if (existing.groupId() == null || existing.artifactId() == null) {
            log.warn("Reflection-group recommendation missing metadata [id={}]", id);
            return ResponseEntity.badRequest().body(Map.of("error", "Reflection group is missing contribution identity metadata (groupId/artifactId)."));
        }

        boolean breakingChange = request != null && request.breakingChange();
        try {
            ContributionVersionRecommendationService.Recommendation recommendation =
                    recommendationService.recommendNextVersion("reflections", existing.groupId(), existing.artifactId(), breakingChange);
            log.debug("EXIT recommendReflectionVersion: id={}, latestVersion={}, recommendedVersion={}",
                    id, recommendation.latestVersion(), recommendation.recommendedVersion());
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "recommendation", recommendation));
        } catch (RuntimeException ex) {
            log.warn("Reflection-group recommendation failed [id={}, groupId={}, artifactId={}]: {}",
                    id, existing.groupId(), existing.artifactId(), ex.getMessage(), ex);
            return ResponseEntity.status(502).body(Map.of("error", "Recommendation failed: " + ex.getMessage()));
        }
    }

    @GetMapping("/surfaces/{id}/recommend-version")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> recommendSurfaceVersionGet(@PathVariable String id,
                                                        @RequestParam(name = "breakingChange", required = false, defaultValue = "false") boolean breakingChange,
                                                        @RequestParam(name = "changeSummary", required = false) String changeSummary) {
        log.debug("ENTER recommendSurfaceVersionGet: id={}, breakingChange={}", id, breakingChange);
        return recommendSurfaceVersion(id, new VersionRecommendationRequest(breakingChange, changeSummary));
    }

    @GetMapping("/skills/{id}/recommend-version")
    @PreAuthorize("hasAuthority('SKILLS_WRITE')")
    public ResponseEntity<?> recommendSkillVersionGet(@PathVariable String id,
                                                      @RequestParam(name = "breakingChange", required = false, defaultValue = "false") boolean breakingChange,
                                                      @RequestParam(name = "changeSummary", required = false) String changeSummary) {
        log.debug("ENTER recommendSkillVersionGet: id={}, breakingChange={}", id, breakingChange);
        return recommendSkillVersion(id, new VersionRecommendationRequest(breakingChange, changeSummary));
    }

    @GetMapping("/reflections/{id}/recommend-version")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> recommendReflectionVersionGet(@PathVariable String id,
                                                           @RequestParam(name = "breakingChange", required = false, defaultValue = "false") boolean breakingChange,
                                                           @RequestParam(name = "changeSummary", required = false) String changeSummary) {
        log.debug("ENTER recommendReflectionVersionGet: id={}, breakingChange={}", id, breakingChange);
        return recommendReflectionVersion(id, new VersionRecommendationRequest(breakingChange, changeSummary));
    }

    @PostMapping("/agents/{id}/snapshot")
    @PreAuthorize("hasAuthority('AGENTS_WRITE')")
    public ResponseEntity<?> createAgentSnapshot(@PathVariable String id) {
        log.debug("ENTER createAgentSnapshot: id={}", id);
        AgentTemplate existing = agentRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.systemAgent()) {
            return ResponseEntity.status(403).body(Map.of("error", "System agents cannot be cloned."));
        }
        if (existing.isSnapshotMutable()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only immutable agents can be cloned to SNAPSHOT."));
        }

        String snapshotUuid = toVid(existing.groupId(), existing.artifactId(), "SNAPSHOT");
        if (agentRepository.get(snapshotUuid) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "SNAPSHOT already exists for this artifact."));
        }

        AgentTemplate snapshot = new AgentTemplate(
                snapshotUuid,
                existing.name(),
                existing.systemPrompt(),
                existing.allowedTools(),
                false,
                existing.skillUuids(),
                existing.agentType(),
                existing.bindingUuids(),
                existing.assignedUsernames(),
                existing.jobUuids(),
                existing.recommendedModel(),
                existing.groupId(),
                existing.artifactId(),
                "SNAPSHOT",
                ArtifactStatus.SNAPSHOT);
        agentRepository.save(snapshot);
        log.info("Agent snapshot created [sourceId={}, snapshotId={}]", existing.uuid(), snapshot.uuid());
        return ResponseEntity.ok(snapshot);
    }

    @PostMapping("/jobs/{id}/snapshot")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> createJobSnapshot(@PathVariable String id,
                                               @AuthenticationPrincipal UserDetails user) {
        log.debug("ENTER createJobSnapshot: id={}, user={}", id, user == null ? null : user.getUsername());
        ScheduledJob existing = jobRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (user == null || user.getUsername() == null || !user.getUsername().equals(existing.userId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Forbidden"));
        }
        if (existing.isSnapshotMutable()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only immutable jobs can be cloned to SNAPSHOT."));
        }

        String snapshotUuid = toVid(existing.groupId(), existing.artifactId(), "SNAPSHOT");
        if (jobRepository.get(snapshotUuid) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "SNAPSHOT already exists for this artifact."));
        }

        ScheduledJob snapshot = new ScheduledJob(
                snapshotUuid,
                existing.name(),
                existing.aiPrompt(),
                existing.sessionUuid(),
                existing.userId(),
                existing.invocationType(),
                existing.startTime(),
                existing.repeatDuration(),
                existing.durationType(),
                existing.lastExecutionTime(),
                existing.nextExecutionTime(),
                existing.agentTemplateId(),
                existing.provider(),
                existing.modelId(),
                existing.oobTimeoutMinutes(),
                existing.expectedOutput(),
                existing.status(),
                existing.skillUuids(),
                existing.toolIds(),
                existing.notificationUserIds(),
                existing.groupId(),
                existing.artifactId(),
                "SNAPSHOT",
                sh.vork.scheduling.domain.ArtifactStatus.SNAPSHOT);
        jobRepository.save(snapshot);
        log.info("Job snapshot created [sourceId={}, snapshotId={}]", existing.id(), snapshot.id());
        return ResponseEntity.ok(snapshot);
    }

    @PostMapping("/surfaces/{id}/snapshot")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> createSurfaceSnapshot(@PathVariable String id) {
        log.debug("ENTER createSurfaceSnapshot: id={}", id);
        Surface existing = surfaceRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.isSnapshotMutable()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only immutable surfaces can be cloned to SNAPSHOT."));
        }

        String snapshotUuid = toVid(existing.groupId(), existing.artifactId(), "SNAPSHOT");
        if (surfaceRepository.get(snapshotUuid) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "SNAPSHOT already exists for this artifact."));
        }

        Surface snapshot = new Surface(
                snapshotUuid,
                existing.toolId(),
                existing.name(),
                existing.description(),
                existing.sessionUuid(),
                existing.executionSessionUuid(),
                existing.skillUuids(),
                existing.reflectionBindingUuids(),
                existing.jobUuids(),
                existing.groupId(),
                existing.artifactId(),
                "SNAPSHOT",
                sh.vork.surface.ArtifactStatus.SNAPSHOT,
                existing.createdAt(),
                System.currentTimeMillis());
        surfaceRepository.save(snapshot);
        log.info("Surface snapshot created [sourceId={}, snapshotId={}]", existing.uuid(), snapshot.uuid());
        return ResponseEntity.ok(snapshot);
    }

    @PostMapping("/skills/{id}/snapshot")
    @PreAuthorize("hasAuthority('SKILLS_WRITE')")
    public ResponseEntity<?> createSkillSnapshot(@PathVariable String id) {
        log.debug("ENTER createSkillSnapshot: id={}", id);
        SkillGroup existing = skillGroupRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.artifactStatus() == sh.vork.skill.ArtifactStatus.SNAPSHOT) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only immutable skill groups can be cloned to SNAPSHOT."));
        }

        String snapshotUuid = toVid(existing.groupId(), existing.artifactId(), "SNAPSHOT");
        if (skillGroupRepository.get(snapshotUuid) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "SNAPSHOT already exists for this artifact."));
        }

        long now = System.currentTimeMillis();
        List<Skill> sourceSkills = skillsForGroup(existing);
        Map<String, String> remappedSkillIds = new LinkedHashMap<>();
        sourceSkills.forEach(skill -> remappedSkillIds.put(skill.uuid(), java.util.UUID.randomUUID().toString()));

        List<Skill> clonedSkills = sourceSkills.stream()
                .map(skill -> cloneSkillForGroup(skill, snapshotUuid, remappedSkillIds.get(skill.uuid()), now, remappedSkillIds))
                .toList();

        SkillGroup snapshot = new SkillGroup(
                snapshotUuid,
                existing.name(),
                existing.author(),
                existing.category(),
                clonedSkills,
                existing.groupId(),
                existing.artifactId(),
                "SNAPSHOT",
                sh.vork.skill.ArtifactStatus.SNAPSHOT,
                now,
                now);

        clonedSkills.forEach(skillRepository::save);
        skillGroupRepository.save(snapshot);
        log.info("Skill-group snapshot created [sourceId={}, snapshotId={}, clonedSkills={}]",
                existing.uuid(), snapshot.uuid(), clonedSkills.size());
        return ResponseEntity.ok(snapshot);
    }

    @PostMapping("/reflections/{id}/snapshot")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> createReflectionSnapshot(@PathVariable String id) {
        log.debug("ENTER createReflectionSnapshot: id={}", id);
        ReflectionGroup existing = reflectionGroupRepository.get(id);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (existing.artifactStatus() == sh.vork.reflection.ArtifactStatus.SNAPSHOT) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only immutable reflection groups can be cloned to SNAPSHOT."));
        }

        String snapshotUuid = toVid(existing.groupId(), existing.artifactId(), "SNAPSHOT");
        if (reflectionGroupRepository.get(snapshotUuid) != null) {
            return ResponseEntity.status(409).body(Map.of("error", "SNAPSHOT already exists for this artifact."));
        }

        long now = System.currentTimeMillis();
        List<Reflection> sourceReflections = reflectionsForGroup(existing.uuid());
        List<ReflectionBinding> sourceBindings = bindingsForGroup(existing.uuid());

        List<Reflection> clonedReflections = sourceReflections.stream()
                .map(reflection -> cloneReflectionForGroup(reflection, snapshotUuid, java.util.UUID.randomUUID().toString(), now))
                .toList();
        List<ReflectionBinding> clonedBindings = sourceBindings.stream()
                .map(binding -> cloneBindingForGroup(binding, snapshotUuid, java.util.UUID.randomUUID().toString(), now))
                .toList();

        ReflectionGroup snapshot = new ReflectionGroup(
                snapshotUuid,
                existing.toolId(),
                existing.name(),
                existing.description(),
                existing.type(),
                existing.baseUrl(),
                existing.urlOverrideEnabled(),
                existing.bindingSecrets(),
                existing.bindingParameters(),
                existing.authenticationMode(),
                existing.oauthTemplateId(),
                existing.groupId(),
                existing.artifactId(),
                "SNAPSHOT",
                sh.vork.reflection.ArtifactStatus.SNAPSHOT,
                now,
                now);

        clonedReflections.forEach(reflectionRepository::save);
        clonedBindings.forEach(reflectionBindingRepository::save);
        reflectionGroupRepository.save(snapshot);
        log.info("Reflection-group snapshot created [sourceId={}, snapshotId={}, clonedReflections={}, clonedBindings={}]",
                existing.uuid(), snapshot.uuid(), clonedReflections.size(), clonedBindings.size());
        return ResponseEntity.ok(snapshot);
    }

    @PostMapping("/promotions/reconcile")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> reconcileContributionPromotions() {
        log.debug("ENTER reconcileContributionPromotions");
        ContributionLifecyclePromotionService.PromotionSummary summary = promotionService.reconcileLifecycleStatuses();
        log.debug("EXIT reconcileContributionPromotions: summary={}", summary);
        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "summary", summary));
    }

    private static String validatePublishRequest(PublishRequest request,
                                                 String groupId,
                                                 String artifactId,
                                                 String artifactKind) {
        if (request == null) {
            return "Publish request is required.";
        }
        if (request.version() == null || request.version().isBlank()) {
            return "version is required.";
        }
        String version = request.version().trim();
        if (!VERSION_PATTERN.matcher(version).matches()) {
            return "version must follow <major>.<minor> format.";
        }
        if ("SNAPSHOT".equalsIgnoreCase(version)) {
            return "version must be a non-SNAPSHOT release version.";
        }
        if (request.prTitle() == null || request.prTitle().isBlank()) {
            return "prTitle is required.";
        }
        if (request.changeSummary() == null || request.changeSummary().isBlank()) {
            return "changeSummary is required.";
        }
        if (groupId == null || groupId.isBlank()
                || artifactId == null || artifactId.isBlank()) {
            return artifactKind + " is missing contribution identity metadata (groupId/artifactId).";
        }
        String logoValidationError = validateOptionalLogoPayload(request.logoBase64(), request.logoFileName());
        if (logoValidationError != null) {
            return logoValidationError;
        }
        return null;
    }

    private static String validateOAuthTemplatePublishRequest(PublishMetadataRequest request,
                                                              OAuthTemplateEntity existing) {
        if (request == null) {
            return "Publish request is required.";
        }
        if (request.prTitle() == null || request.prTitle().isBlank()) {
            return "prTitle is required.";
        }
        if (request.changeSummary() == null || request.changeSummary().isBlank()) {
            return "changeSummary is required.";
        }
        if (existing == null || existing.clientName() == null || existing.clientName().isBlank()) {
            return "OAuth template is missing clientName.";
        }
        String logoValidationError = validateOptionalLogoPayload(request.logoBase64(), request.logoFileName());
        if (logoValidationError != null) {
            return logoValidationError;
        }
        return null;
    }

    private static String validateOptionalLogoPayload(String logoBase64, String logoFileName) {
        boolean hasLogoContent = logoBase64 != null && !logoBase64.isBlank();
        boolean hasLogoFileName = logoFileName != null && !logoFileName.isBlank();
        if (hasLogoContent != hasLogoFileName) {
            return "logoBase64 and logoFileName must be provided together.";
        }
        if (!hasLogoContent) {
            return null;
        }
        String ext = normalizeLogoExtension(logoFileName);
        if (ext == null) {
            return "logoFileName must end with one of: .svg, .png, .jpg, .jpeg, .gif, .webp";
        }
        try {
            Base64.getDecoder().decode(logoBase64.trim());
        } catch (IllegalArgumentException ex) {
            return "logoBase64 must be valid base64-encoded image content.";
        }
        return null;
    }

    private static void appendArtifactLogoIfPresent(List<ContributionFile> files,
                                                    String logoBase64,
                                                    String logoFileName,
                                                    String artifactBasePath) {
        appendNamedLogoIfPresent(files, logoBase64, logoFileName, artifactBasePath + "/logo");
    }

    private static void appendNamedLogoIfPresent(List<ContributionFile> files,
                                                 String logoBase64,
                                                 String logoFileName,
                                                 String pathWithoutExtension) {
        if (logoBase64 == null || logoBase64.isBlank()) {
            return;
        }
        String ext = normalizeLogoExtension(logoFileName);
        if (ext == null) {
            throw new IllegalArgumentException("Unsupported logo extension.");
        }
        files.add(ContributionFile.base64(pathWithoutExtension + "." + ext, logoBase64.trim()));
    }

    private static String normalizeLogoExtension(String logoFileName) {
        if (logoFileName == null || logoFileName.isBlank()) {
            return null;
        }
        String lower = logoFileName.trim().toLowerCase();
        if (lower.endsWith(".svg")) return "svg";
        if (lower.endsWith(".png")) return "png";
        if (lower.endsWith(".jpg")) return "jpg";
        if (lower.endsWith(".jpeg")) return "jpeg";
        if (lower.endsWith(".gif")) return "gif";
        if (lower.endsWith(".webp")) return "webp";
        return null;
    }

    private static ResponseEntity<?> dependencyFailureIfAny(ContributionDependencyValidator.DependencyValidationReport report) {
        if (report == null || report.valid()) {
            return null;
        }
        return ResponseEntity.status(409).body(Map.of(
                "error", report.summary(),
                "dependencyReport", report));
    }

    private static String toVid(String groupId, String artifactId, String version) {
        return groupId + "-" + artifactId + "-" + version;
    }

    private static String buildBranchName(String type, String groupId, String artifactId, String version) {
        String safeType = sanitizeSegment(type);
        String safeGroup = sanitizeSegment(groupId);
        String safeArtifact = sanitizeSegment(artifactId);
        String safeVersion = sanitizeSegment(version);
        long timestamp = Instant.now().getEpochSecond();
        return "contrib/" + safeType + "/" + safeGroup + "-" + safeArtifact + "-" + safeVersion + "-" + timestamp;
    }

    private static String buildBranchNameNoVersion(String type, String identity) {
        String safeType = sanitizeSegment(type);
        String safeIdentity = sanitizeSegment(identity);
        long timestamp = Instant.now().getEpochSecond();
        return "contrib/" + safeType + "/" + safeIdentity + "-" + timestamp;
    }

    private String readSurfaceAsset(Surface surface,
                                    String username,
                                    String relativePath,
                                    String fallback) {
        if (username == null || username.isBlank()) {
            return fallback;
        }
        try {
            String sessionUuid = surfaceService.ensureSession(surface.uuid(), username).uuid();
            try (var in = sessionFileSystem.read(FileArea.SESSION, sessionUuid, relativePath)) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException ex) {
            log.warn("Surface asset unavailable for contribution [surface={}, path={}]: {}",
                    surface.uuid(), relativePath, ex.getMessage());
            return fallback;
        } catch (IOException ex) {
            log.warn("Surface asset read failed for contribution [surface={}, path={}]: {}",
                    surface.uuid(), relativePath, ex.getMessage());
            return fallback;
        }
    }

    private static String sanitizeSegment(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        return input.trim().toLowerCase().replaceAll("[^a-z0-9.-]+", "-");
    }

    private PublishDraft buildPublishDraft(String artifactTypePath,
                                           String artifactUuid,
                                           String groupId,
                                           String artifactId,
                                           String noun,
                                           Object newArtifact,
                                           Object previousArtifact,
                                           String additionalContext,
                                           List<Media> additionalMedia) {
        if (groupId == null || groupId.isBlank() || artifactId == null || artifactId.isBlank()) {
            throw new IllegalStateException("Artifact is missing contribution identity metadata (groupId/artifactId).");
        }

        ContributionVersionRecommendationService.Recommendation minorRec =
                recommendationService.recommendNextVersion(artifactTypePath, groupId, artifactId, false);
        ContributionVersionRecommendationService.Recommendation majorRec =
                recommendationService.recommendNextVersion(artifactTypePath, groupId, artifactId, true);

        String defaultVersion = nonBlank(minorRec.recommendedVersion(), "1.0");

        String artifactInstructions;

        switch(artifactTypePath) {
            case "agents" -> artifactInstructions =
                """
                This is an agent template artifact. It provides instructions for an AI agent's behavior, including its 
                system prompt, allowed tools, skills, and other configuration details.
                """;
            case "jobs" -> artifactInstructions =
                """
                This is a job template artifact. It defines a scheduled job's configuration, including its name, 
                AI prompt, session UUID, user ID, and other job-specific settings.
                """;
            case "surfaces" -> artifactInstructions =
                """
                This is a surface template artifact. It describes a user interface surface's configuration, including 
                its name, description, associated skills, and other surface-specific settings. Surfaces have associated 
                assets like HTML and JavaScript files that define their appearance and behavior. These are also provided
                as additional files in the contribution and should also be reviewed as part of this request.
                """;
            case "skills" -> artifactInstructions =
                """
                This is a skill template artifact.
                """;
            case "reflections" -> artifactInstructions =
                """
                This is a reflection group artifact. It includes group metadata, reflection definitions,
                and binding configuration metadata used to execute those reflections.
                """;
            default -> artifactInstructions = "This is an unknown artifact type.";
        }

        String previousJson = toCompactJson(previousArtifact);
        String newJson = toCompactJson(newArtifact);
        String prompt = """
                You are preparing a GitHub pull request draft for a %s artifact.

                %s

                You will be provided with the previous artifact JSON (if available) and the new artifact JSON. Compare 
                them and determine if there are any breaking changes. Then, generate a concise PR title, change summary, 
                commit message, PR body, release notes, and reviewer hints.

                Writing style requirements:
                - Write in natural, human language for engineers/reviewers.
                - Explain what this artifact is and what it does in practical terms.
                - Avoid boilerplate/clinical wording like "Publish artifact ...".
                - Prefer specific statements such as "Adds a new surface for ..." or "Improves job ...".

                Return STRICT JSON only (no markdown, no prose) using exactly these fields:
                version, breakingChange, prTitle, changeSummary, commitMessage, prBody, releaseNotes, reviewerHints.

                Field quality requirements:
                - releaseNotes must be non-empty and specific to the artifact changes.
                - reviewerHints must be non-empty and specific to validation/review focus areas.
                - releaseNotes and reviewerHints should each contain 2-6 concise bullet points.

                Version rules:
                - Must match major.minor numeric format.
                - If change is breaking, prefer a major bump.
                - If change is non-breaking, prefer a minor bump.
                - Never return SNAPSHOT or any other markers. The version is strictly <major>.<minor> format.

                Context:
                artifactTypePath: %s
                artifactUuid: %s
                groupId: %s
                artifactId: %s
                latestVersionInStaging: %s
                recommendedMinorVersion: %s
                recommendedMajorVersion: %s
                previousArtifactJson: %s
                newArtifactJson: %s
                additionalContent: %s

                Provide concise, actionable PR metadata.
                """.formatted(
                artifactTypePath,
                artifactInstructions,
                artifactTypePath,
                artifactUuid,
                groupId,
                artifactId,
                nonBlank(minorRec.latestVersion(), "none"),
                nonBlank(minorRec.recommendedVersion(), "1.0"),
                nonBlank(majorRec.recommendedVersion(), "1.0"),
                previousJson,
                newJson,
                nonBlank(additionalContext, "none"));

        String aiRaw = null;
        try {
            AiProvider provider = resolveDefaultProvider();
            if (additionalMedia != null && !additionalMedia.isEmpty()) {
                aiRaw = aiOrchestrationService.generateWithHistoryAndMedia(
                        List.<Message>of(),
                        prompt,
                        additionalMedia,
                        provider);
            } else {
                aiRaw = aiOrchestrationService.generate(prompt, provider);
            }
            log.debug("AI draft raw response [artifactType={}, artifactUuid={}]: {}",
                    artifactTypePath, artifactUuid, aiRaw);
        } catch (RuntimeException ex) {
            log.warn("AI publish draft generation failed; using defaults [artifactType={}, artifactUuid={}]: {}",
                    artifactTypePath, artifactUuid, ex.getMessage());
        }

        Map<String, Object> ai = parseJsonObjectFromText(aiRaw);
        log.debug("AI draft parsed payload [artifactType={}, artifactUuid={}]: {}",
                artifactTypePath, artifactUuid, ai);
        String artifactHeadline = describeArtifactHeadline(noun, artifactUuid, newArtifact);
        String artifactPurpose = describeArtifactPurpose(newArtifact);
        boolean breakingChange = asBoolean(ai.get("breakingChange"));
        String version = normalizedVersion(asString(ai.get("version")),
                breakingChange
                        ? nonBlank(majorRec.recommendedVersion(), defaultVersion)
                        : defaultVersion);
        String changeSummary = humanizeIfGeneric(
            nonBlank(asString(ai.get("changeSummary")), defaultChangeSummary(noun, artifactHeadline, artifactPurpose, version)),
            defaultChangeSummary(noun, artifactHeadline, artifactPurpose, version));
        String prTitle = humanizeIfGeneric(
            nonBlank(asString(ai.get("prTitle")), defaultPrTitle(noun, artifactHeadline, version)),
            defaultPrTitle(noun, artifactHeadline, version));
        String commitMessage = humanizeCommitMessage(
            asString(ai.get("commitMessage")),
            defaultCommitMessage(noun, artifactHeadline, version, changeSummary, previousArtifact == null));
        String prBody = humanizeIfGeneric(
            nonBlank(asString(ai.get("prBody")), defaultPrBody(noun, artifactHeadline, artifactPurpose, changeSummary, additionalContext)),
            defaultPrBody(noun, artifactHeadline, artifactPurpose, changeSummary, additionalContext));
        String releaseNotes = nonBlank(
            asString(ai.get("releaseNotes")),
            defaultReleaseNotes(noun, artifactHeadline, version, changeSummary, additionalContext));
        String reviewerHints = nonBlank(
            asString(ai.get("reviewerHints")),
            defaultReviewerHints(noun, artifactHeadline, version, additionalContext));

        log.debug("AI draft resolved fields [artifactType={}, artifactUuid={}, version={}, breakingChange={}, prTitle={}, changeSummary={}, commitMessage={}, prBody={}, releaseNotes={}, reviewerHints={}]",
            artifactTypePath,
            artifactUuid,
            version,
            breakingChange,
            prTitle,
            changeSummary,
            commitMessage,
            prBody,
            releaseNotes,
            reviewerHints);

        return new PublishDraft(
                version,
                commitMessage,
                prTitle,
                prBody,
                changeSummary,
                breakingChange,
                releaseNotes,
                reviewerHints,
                minorRec.latestVersion());
    }

    private void appendSurfaceAssetsAsMedia(Surface surface,
                                            String username,
                                            String phase,
                                            List<Media> media,
                                            StringBuilder context) {
        if (surface == null || username == null || username.isBlank()) {
            return;
        }
        try {
            String sessionUuid = surfaceService.ensureSession(surface.uuid(), username).uuid();
            List<SurfaceAsset> assets = listSurfaceAssets(sessionUuid);
            if (assets.isEmpty()) {
                context.append(phase).append("SurfaceAssets: none\n");
                return;
            }
            context.append(phase).append("SurfaceAssets:\n");
            for (SurfaceAsset asset : assets) {
                context.append("- ").append(asset.relativePath()).append("\n");
                media.add(new Media(
                        MimeType.valueOf(asset.mimeType()),
                        new NamedByteArrayResource(asset.content(), phase + "/" + asset.relativePath())));
            }
        } catch (RuntimeException ex) {
            log.warn("Unable to collect surface assets for draft context [surface={}, phase={}]: {}",
                    surface.uuid(), phase, ex.getMessage());
            context.append(phase).append("SurfaceAssets: unavailable\n");
        }
    }

    private List<SurfaceAsset> listSurfaceAssets(String sessionUuid) {
        List<SurfaceAsset> out = new ArrayList<>();
        collectSurfaceAssetsRecursive(sessionUuid, "", out);
        return out;
    }

    private void collectSurfaceAssetsRecursive(String sessionUuid,
                                               String relativeDir,
                                               List<SurfaceAsset> out) {
        try {
            List<sh.vork.filesystem.FileNode> nodes = sessionFileSystem.list(FileArea.SESSION, sessionUuid, relativeDir);
            for (sh.vork.filesystem.FileNode node : nodes) {
                if (node == null) {
                    continue;
                }
                if (node.directory()) {
                    collectSurfaceAssetsRecursive(sessionUuid, node.path(), out);
                    continue;
                }
                try (InputStream in = sessionFileSystem.read(FileArea.SESSION, sessionUuid, node.path())) {
                    byte[] bytes = in.readAllBytes();
                    out.add(new SurfaceAsset(node.path(), inferMimeType(node.path()), bytes));
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to enumerate surface asset files", ex);
        }
    }

    private static String inferMimeType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private AiProvider resolveDefaultProvider() {
        SystemSettings settings = systemSettingsService != null ? systemSettingsService.getGlobal() : null;
        String configured = settings != null ? settings.defaultProvider() : null;
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("No default AI provider configured in system settings.");
        }
        try {
            AiProvider provider = AiProvider.valueOf(configured.trim().toUpperCase());
            if (provider == AiProvider.BACKGROUND_SCHEDULER || provider == AiProvider.ANTHROPIC) {
                throw new IllegalStateException("Unsupported default AI provider for contribution drafts: " + provider);
            }
            return provider;
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("Invalid default AI provider configured: " + configured, ex);
        }
    }

    private AgentTemplate maybeAgentPrevious(String groupId, String artifactId) {
        String latest = latestVersionOrNull("agents", groupId, artifactId);
        if (latest == null) return null;
        return agentRepository.get(toVid(groupId, artifactId, latest));
    }

    private ScheduledJob maybeJobPrevious(String groupId, String artifactId) {
        String latest = latestVersionOrNull("jobs", groupId, artifactId);
        if (latest == null) return null;
        return jobRepository.get(toVid(groupId, artifactId, latest));
    }

    private Surface maybeSurfacePrevious(String groupId, String artifactId) {
        String latest = latestVersionOrNull("surfaces", groupId, artifactId);
        if (latest == null) return null;
        return surfaceRepository.get(toVid(groupId, artifactId, latest));
    }

    private Map<String, Object> maybeSkillPreviousArtifact(String groupId, String artifactId) {
        String latest = latestVersionOrNull("skills", groupId, artifactId);
        if (latest == null) return null;
        SkillGroup previous = skillGroupRepository.get(toVid(groupId, artifactId, latest));
        if (previous == null) return null;
        return toSkillArtifactPackage(previous, skillsForGroup(previous));
    }

    private Map<String, Object> maybeReflectionPreviousArtifact(String groupId, String artifactId) {
        String latest = latestVersionOrNull("reflections", groupId, artifactId);
        if (latest == null) return null;
        ReflectionGroup previous = reflectionGroupRepository.get(toVid(groupId, artifactId, latest));
        if (previous == null) return null;
        return toReflectionArtifactPackage(previous,
                reflectionsForGroup(previous.uuid()),
                bindingsForGroup(previous.uuid()));
    }

    private Map<String, Object> toSkillArtifactPackage(SkillGroup group, List<Skill> skills) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("group", group);
        artifact.put("skills", skills == null ? List.of() : skills);
        return artifact;
    }

    private Map<String, Object> toReflectionArtifactPackage(ReflectionGroup group,
                                                            List<Reflection> reflections,
                                                            List<ReflectionBinding> bindings) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("group", group);
        artifact.put("reflections", reflections == null ? List.of() : reflections);
        artifact.put("bindings", bindings == null ? List.of() : bindings);
        return artifact;
    }

        private Map<String, Object> toOAuthTemplateContributionArtifact(OAuthTemplateEntity template) {
        Map<String, Object> artifact = new LinkedHashMap<>();
        artifact.put("name", template.name());
        artifact.put("clientName", template.clientName());
        artifact.put("description", template.description());
        artifact.put("authorizeEndpoint", template.authorizeEndpoint());
        artifact.put("tokenEndpoint", template.tokenEndpoint());
        artifact.put("scopes", template.scopes() == null ? List.of() : template.scopes());
        artifact.put("authorizationParameters", template.authorizationParameters() == null ? Map.of() : template.authorizationParameters());
        return artifact;
        }

        private PublishMetadataDraft buildOAuthTemplatePublishDraft(OAuthTemplateEntity existing) {
        Map<String, Object> artifact = toOAuthTemplateContributionArtifact(existing);
        String prompt = """
            You are preparing a GitHub pull request draft for an OAuth template artifact.

            OAuth templates are not versioned. The artifact will be committed to:
            oauth-templates/<clientName>.json

            Return STRICT JSON only (no markdown, no prose) with exactly these fields:
            prTitle, changeSummary, commitMessage, prBody, releaseNotes, reviewerHints.

            Writing style requirements:
            - Write clear, practical language for engineering reviewers.
            - Explain what provider/template was added or changed.
            - Do not include placeholder text.
            - releaseNotes and reviewerHints must each be non-empty and include 2-6 concise bullet points.

            Context:
            templateUuid: %s
            clientName: %s
            artifactJson: %s
            """.formatted(
            nonBlank(existing.uuid(), "unknown"),
            nonBlank(existing.clientName(), "unknown"),
            toCompactJson(artifact));

        String aiRaw = null;
        try {
            AiProvider provider = resolveDefaultProvider();
            aiRaw = aiOrchestrationService.generate(prompt, provider);
            log.debug("AI draft raw response [artifactType=oauth-template, artifactUuid={}]: {}", existing.uuid(), aiRaw);
        } catch (RuntimeException ex) {
            log.warn("AI publish draft generation failed; using defaults [artifactType=oauth-template, artifactUuid={}]: {}",
                existing.uuid(), ex.getMessage());
        }

        Map<String, Object> ai = parseJsonObjectFromText(aiRaw);
        String headline = "\"" + nonBlank(existing.name(), nonBlank(existing.clientName(), "oauth template")) + "\"";
        String clientName = nonBlank(existing.clientName(), "oauth-template");
        String changeSummary = humanizeIfGeneric(
            nonBlank(asString(ai.get("changeSummary")), "Updates OAuth template " + headline + " for clientName " + clientName + "."),
            "Updates OAuth template " + headline + " for clientName " + clientName + ".");
        String prTitle = humanizeIfGeneric(
            nonBlank(asString(ai.get("prTitle")), "Update OAuth template " + headline),
            "Update OAuth template " + headline);
        String commitMessage = humanizeCommitMessage(
            asString(ai.get("commitMessage")),
            "contrib(oauth-template): update \"" + clientName + "\"");
        String prBody = humanizeIfGeneric(
            nonBlank(asString(ai.get("prBody")),
                "## What this changes\n" + changeSummary + "\n\n## Artifact path\n- oauth-templates/" + clientName + ".json"),
            "## What this changes\n" + changeSummary + "\n\n## Artifact path\n- oauth-templates/" + clientName + ".json");
        String releaseNotes = nonBlank(
            asString(ai.get("releaseNotes")),
            "- Updated OAuth template " + headline + ".\n- Artifact path: oauth-templates/" + clientName + ".json");
        String reviewerHints = nonBlank(
            asString(ai.get("reviewerHints")),
            "- Verify authorize and token endpoints.\n- Verify scopes and authorization parameter compatibility.");

        return new PublishMetadataDraft(commitMessage, prTitle, prBody, changeSummary, releaseNotes, reviewerHints);
        }

    private List<Skill> skillsForGroup(SkillGroup group) {
        if (group == null) {
            return List.of();
        }
        if (group.skills() != null && !group.skills().isEmpty()) {
            return group.skills();
        }
        String groupUuid = group.uuid();
        if (groupUuid == null || groupUuid.isBlank()) {
            return List.of();
        }
        List<Skill> matches = new ArrayList<>();
        long total = skillRepository.count();
        int pageSize = 200;
        int pages = (int) ((total + pageSize - 1) / pageSize);
        for (int page = 0; page < pages; page++) {
            try (var stream = skillRepository.list(page, pageSize)) {
                stream.filter(skill -> skill != null && groupUuid.equals(skill.groupUuid()))
                        .forEach(matches::add);
            }
        }
        return matches;
    }

    private List<Reflection> reflectionsForGroup(String groupUuid) {
        if (groupUuid == null || groupUuid.isBlank()) {
            return List.of();
        }
        List<Reflection> matches = new ArrayList<>();
        long total = reflectionRepository.count();
        int pageSize = 200;
        int pages = (int) ((total + pageSize - 1) / pageSize);
        for (int page = 0; page < pages; page++) {
            try (var stream = reflectionRepository.list(page, pageSize)) {
                stream.filter(reflection -> reflection != null && groupUuid.equals(reflection.groupUuid()))
                        .forEach(matches::add);
            }
        }
        return matches;
    }

    private List<ReflectionBinding> bindingsForGroup(String groupUuid) {
        if (groupUuid == null || groupUuid.isBlank()) {
            return List.of();
        }
        List<ReflectionBinding> matches = new ArrayList<>();
        long total = reflectionBindingRepository.count();
        int pageSize = 200;
        int pages = (int) ((total + pageSize - 1) / pageSize);
        for (int page = 0; page < pages; page++) {
            try (var stream = reflectionBindingRepository.list(page, pageSize)) {
                stream.filter(binding -> binding != null && groupUuid.equals(binding.groupUuid()))
                        .forEach(matches::add);
            }
        }
        return matches;
    }

    private Skill cloneSkillForGroup(Skill source,
                                     String targetGroupUuid,
                                     String targetSkillUuid,
                                     long now,
                                     Map<String, String> remappedSkillIds) {
        List<String> remappedSubSkills = source.subSkillUuids() == null
                ? List.of()
                : source.subSkillUuids().stream()
                .map(sub -> remappedSkillIds != null && remappedSkillIds.containsKey(sub)
                        ? remappedSkillIds.get(sub)
                        : sub)
                .toList();

        return new Skill(
                targetSkillUuid,
                source.name(),
                source.description(),
                targetGroupUuid,
                source.visibility(),
                source.parameters(),
                source.instructions(),
                source.allowedTools(),
                source.allowedTypes(),
                remappedSubSkills,
                source.recommendedModel(),
                source.outputContentType(),
                source.outputSchema(),
                source.version(),
                now,
                now,
                source.secrets(),
                source.bindingUuids());
    }

            private Reflection cloneReflectionForGroup(Reflection source,
                                   String targetGroupUuid,
                                   String targetUuid,
                                   long now) {
            return new Reflection(
                targetUuid,
                source.id(),
                source.name(),
                source.description(),
                targetGroupUuid,
                source.inputParameters(),
                source.method(),
                source.url(),
                source.headers(),
                source.queryParameters(),
                source.bodyTemplate(),
                source.requestContentType(),
                source.responseContentType(),
                source.outputSchema(),
                source.version(),
                source.createdAt(),
                now);
            }

            private ReflectionBinding cloneBindingForGroup(ReflectionBinding source,
                                   String targetGroupUuid,
                                   String targetUuid,
                                   long now) {
            return new ReflectionBinding(
                targetUuid,
                targetGroupUuid,
                source.name(),
                source.baseUrl(),
                source.parameterValues(),
                source.version(),
                source.createdAt(),
                now);
            }

    private String latestVersionOrNull(String artifactTypePath, String groupId, String artifactId) {
        try {
            return recommendationService
                    .recommendNextVersion(artifactTypePath, groupId, artifactId, false)
                    .latestVersion();
        } catch (RuntimeException ex) {
            log.warn("Unable to resolve latest version for AI draft context [type={}, groupId={}, artifactId={}]: {}",
                    artifactTypePath, groupId, artifactId, ex.getMessage());
            return null;
        }
    }

    private String toCompactJson(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return "null";
        }
    }

    private Map<String, Object> parseJsonObjectFromText(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(text, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (Exception ignore) {
            // fall through to brace extraction
        }

        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(text.substring(start, end + 1), Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (Exception ignore) {
            return Map.of();
        }
        return Map.of();
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return false;
        }
        String normalized = String.valueOf(value).trim().toLowerCase();
        return "true".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized);
    }

    private static String normalizedVersion(String candidate, String fallback) {
        String version = nonBlank(candidate, fallback);
        if (!VERSION_PATTERN.matcher(version).matches() || "SNAPSHOT".equalsIgnoreCase(version)) {
            return fallback;
        }
        return version;
    }

    private static String nonBlank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String defaultReleaseNotes(String noun,
                                              String artifactHeadline,
                                              String version,
                                              String changeSummary,
                                              String additionalContext) {
        String summary = nonBlank(changeSummary, "Artifact update for " + artifactHeadline + ".");
        String filesHint = compactAssetHint(additionalContext);
        StringBuilder notes = new StringBuilder();
        notes.append("- Released ").append(noun).append(" ").append(artifactHeadline)
            .append(" as version ").append(version).append(".\n")
                .append("- Change summary: ").append(summary).append("\n")
                .append("- Versioning intent follows contribution policy (major/minor based on compatibility).\n");
        if (!filesHint.isBlank()) {
            notes.append("- Included asset/files context: ").append(filesHint).append("\n");
        }
        return notes.toString().trim();
    }

    private static String defaultReviewerHints(String noun,
                                               String artifactHeadline,
                                               String version,
                                               String additionalContext) {
        String filesHint = compactAssetHint(additionalContext);
        StringBuilder hints = new StringBuilder();
        hints.append("- Verify version bump rationale for ").append(artifactHeadline).append(" -> ").append(version).append(".\n")
                .append("- Review functional impact and backward compatibility expectations for the ").append(noun).append(" artifact.\n")
                .append("- Validate staged artifact content and metadata completeness (IDs, version, status).\n");
        if (!filesHint.isBlank()) {
            hints.append("- Spot-check changed assets/files: ").append(filesHint).append("\n");
        }
        return hints.toString().trim();
    }

    private static String compactAssetHint(String additionalContext) {
        if (additionalContext == null || additionalContext.isBlank()) {
            return "";
        }
        String[] lines = additionalContext.split("\\R");
        List<String> assets = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith("- ")) {
                assets.add(trimmed.substring(2));
            }
            if (assets.size() >= 3) {
                break;
            }
        }
        if (assets.isEmpty()) {
            return "";
        }
        return String.join(", ", assets);
    }

    private static String defaultChangeSummary(String noun,
                                               String artifactHeadline,
                                               String artifactPurpose,
                                               String version) {
        return "Introduces " + noun + " " + artifactHeadline + " as version " + version
                + " to " + artifactPurpose + ".";
    }

    private static String defaultPrTitle(String noun,
                                         String artifactHeadline,
                                         String version) {
        return "Add " + noun + " " + artifactHeadline + " (v" + version + ")";
    }

    private static String defaultPrBody(String noun,
                                        String artifactHeadline,
                                        String artifactPurpose,
                                        String changeSummary,
                                        String additionalContext) {
        String filesHint = compactAssetHint(additionalContext);
        StringBuilder body = new StringBuilder();
        body.append("## What this adds\n")
                .append(changeSummary)
                .append("\n\n## Why\n")
                .append("This ").append(noun).append(" adds support for ").append(artifactPurpose).append(".");
        if (!filesHint.isBlank()) {
            body.append("\n\n## Included files\n")
                    .append("- ").append(filesHint);
        }
        return body.toString();
    }

    private static String defaultCommitMessage(String noun,
                                               String artifactHeadline,
                                               String version,
                                               String changeSummary,
                                               boolean newArtifact) {
        String headline = trimQuotes(nonBlank(artifactHeadline, "artifact"));
        if (newArtifact) {
            return "contrib(" + noun + "): Introduces " + noun + " \"" + headline + "\" as version " + version;
        }
        String context = summarizeForCommit(nonBlank(changeSummary, ""));
        if (!context.isBlank()) {
            return "contrib(" + noun + "): Updates " + noun + " \"" + headline + "\" for version " + version + " - " + context;
        }
        return "contrib(" + noun + "): Updates " + noun + " \"" + headline + "\" to version " + version;
    }

    private static String humanizeCommitMessage(String candidate,
                                                String fallback) {
        if (candidate == null || candidate.isBlank()) {
            return fallback;
        }
        String normalized = candidate.trim();
        String lower = normalized.toLowerCase();
        if (lower.matches("^contrib\\([^)]+\\):\\s*publish\\s+\\S+\\s+\\d+\\.\\d+\\s*$")
                || lower.matches("^contrib\\([^)]+\\):\\s*update\\s+\\S+\\s+\\d+\\.\\d+\\s*$")) {
            return fallback;
        }
        return normalized;
    }

    private static String summarizeForCommit(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        compact = compact.replaceAll("^[\\-\\u2022\\s]+", "");
        compact = compact.replaceAll("[.]+$", "");
        if (compact.length() > 72) {
            compact = compact.substring(0, 72).trim() + "...";
        }
        return compact;
    }

    private static String trimQuotes(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static boolean isFirstReleaseVersion(String version) {
        return "1.0".equals(version == null ? "" : version.trim());
    }

    private static String describeArtifactHeadline(String noun,
                                                   String artifactUuid,
                                                   Object artifact) {
        if (artifact instanceof AgentTemplate a && a.name() != null && !a.name().isBlank()) {
            return "\"" + a.name().trim() + "\"";
        }
        if (artifact instanceof ScheduledJob j && j.name() != null && !j.name().isBlank()) {
            return "\"" + j.name().trim() + "\"";
        }
        if (artifact instanceof Surface s && s.name() != null && !s.name().isBlank()) {
            return "\"" + s.name().trim() + "\"";
        }
        if (artifact instanceof SkillGroup g && g.name() != null && !g.name().isBlank()) {
            return "\"" + g.name().trim() + "\"";
        }
        if (artifact instanceof Map<?, ?> map && map.get("group") instanceof SkillGroup g && g.name() != null && !g.name().isBlank()) {
            return "\"" + g.name().trim() + "\"";
        }
        if (artifact instanceof ReflectionGroup g && g.name() != null && !g.name().isBlank()) {
            return "\"" + g.name().trim() + "\"";
        }
        if (artifact instanceof Map<?, ?> map && map.get("group") instanceof ReflectionGroup g && g.name() != null && !g.name().isBlank()) {
            return "\"" + g.name().trim() + "\"";
        }
        return "\"" + noun + " " + artifactUuid + "\"";
    }

    private static String describeArtifactPurpose(Object artifact) {
        if (artifact instanceof Surface s) {
            String description = s.description();
            if (description != null && !description.isBlank()) {
                return description.trim();
            }
            return "provide a user-facing workflow";
        }
        if (artifact instanceof ScheduledJob j) {
            String prompt = j.aiPrompt();
            if (prompt != null && !prompt.isBlank()) {
                return "automate \"" + summarize(prompt) + "\"";
            }
            return "automate a background process";
        }
        if (artifact instanceof AgentTemplate a) {
            String systemPrompt = a.systemPrompt();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                return "support \"" + summarize(systemPrompt) + "\"";
            }
            return "support an interactive agent workflow";
        }
        if (artifact instanceof SkillGroup g) {
            String category = g.category();
            if (category != null && !category.isBlank()) {
                return "provide reusable " + category.trim() + " skills";
            }
            return "provide reusable skill workflows";
        }
        if (artifact instanceof Map<?, ?> map && map.get("group") instanceof SkillGroup g) {
            String category = g.category();
            if (category != null && !category.isBlank()) {
                return "provide reusable " + category.trim() + " skills";
            }
            return "provide reusable skill workflows";
        }
        return "deliver the intended capability";
    }

    private static String summarize(String text) {
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 80) {
            return compact;
        }
        return compact.substring(0, 80).trim() + "...";
    }

    private static String humanizeIfGeneric(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim();
        String lower = normalized.toLowerCase();
        if (lower.startsWith("publish artifact ")
                || lower.startsWith("publish ")
                || lower.equals("n/a")
                || lower.equals("na")) {
            return fallback;
        }
        return normalized;
    }

    private static String submissionKey(String artifactType, String artifactUuid) {
        return artifactType + ":" + artifactUuid;
    }

    private record SurfaceAsset(String relativePath, String mimeType, byte[] content) {
    }

    private static final class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        private NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray == null ? new byte[0] : byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }

    public record PublishRequest(
            String version,
            String commitMessage,
            String prTitle,
            String prBody,
            String changeSummary,
            boolean breakingChange,
            String releaseNotes,
            String reviewerHints,
            String logoBase64,
            String logoFileName
    ) {
        public PublishRequest(String version,
                              String commitMessage,
                              String prTitle,
                              String prBody,
                              String changeSummary,
                              boolean breakingChange,
                              String releaseNotes,
                              String reviewerHints) {
            this(version, commitMessage, prTitle, prBody, changeSummary, breakingChange, releaseNotes, reviewerHints, null, null);
        }
    }

            public record PublishDraftRequest(String context) {
            }

            public record PublishDraft(
                String version,
                String commitMessage,
                String prTitle,
                String prBody,
                String changeSummary,
                boolean breakingChange,
                String releaseNotes,
                String reviewerHints,
                String latestVersion
            ) {
            }

            public record PublishMetadataRequest(
                String commitMessage,
                String prTitle,
                String prBody,
                String changeSummary,
                String releaseNotes,
                String reviewerHints,
                String logoBase64,
                String logoFileName
            ) {
                public PublishMetadataRequest(String commitMessage,
                                              String prTitle,
                                              String prBody,
                                              String changeSummary,
                                              String releaseNotes,
                                              String reviewerHints) {
                    this(commitMessage, prTitle, prBody, changeSummary, releaseNotes, reviewerHints, null, null);
                }
            }

            public record PublishMetadataDraft(
                String commitMessage,
                String prTitle,
                String prBody,
                String changeSummary,
                String releaseNotes,
                String reviewerHints
            ) {
            }

    public record VersionRecommendationRequest(boolean breakingChange,
                                               String changeSummary) {
    }
}
