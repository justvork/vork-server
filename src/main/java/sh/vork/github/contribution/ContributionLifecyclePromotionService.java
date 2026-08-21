package sh.vork.github.contribution;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.orm.DatabaseEntity;
import sh.vork.orm.DatabaseRepository;
import sh.vork.oauth.OAuthTemplateEntity;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.skill.SkillGroup;
import sh.vork.surface.Surface;

@Service
public class ContributionLifecyclePromotionService {

    private static final Logger log = LoggerFactory.getLogger(ContributionLifecyclePromotionService.class);

    private static final String OFFICIAL_OWNER = "justvork";
    private static final String OFFICIAL_REPOSITORY = "vork-central";
    private static final String STAGING_BRANCH = "staging";
    private static final String MAIN_BRANCH = "main";
    private static final int PAGE_SIZE = 200;

    private final DatabaseRepository<AgentTemplate> agentRepository;
    private final DatabaseRepository<ScheduledJob> jobRepository;
    private final DatabaseRepository<Surface> surfaceRepository;
    private final DatabaseRepository<SkillGroup> skillGroupRepository;
    private final DatabaseRepository<ReflectionGroup> reflectionGroupRepository;
    private final DatabaseRepository<OAuthTemplateEntity> oauthTemplateRepository;
    private final DatabaseRepository<ContributionSubmission> contributionSubmissionRepository;
    private final GitHubContributionApiClient contributionApiClient;

    public ContributionLifecyclePromotionService(DatabaseRepository<AgentTemplate> agentRepository,
                                                DatabaseRepository<ScheduledJob> jobRepository,
                                                DatabaseRepository<Surface> surfaceRepository,
                                                DatabaseRepository<SkillGroup> skillGroupRepository,
                                                DatabaseRepository<ReflectionGroup> reflectionGroupRepository,
                                                DatabaseRepository<OAuthTemplateEntity> oauthTemplateRepository,
                                                DatabaseRepository<ContributionSubmission> contributionSubmissionRepository,
                                                GitHubContributionApiClient contributionApiClient) {
        this.agentRepository = agentRepository;
        this.jobRepository = jobRepository;
        this.surfaceRepository = surfaceRepository;
        this.skillGroupRepository = skillGroupRepository;
        this.reflectionGroupRepository = reflectionGroupRepository;
        this.oauthTemplateRepository = oauthTemplateRepository;
        this.contributionSubmissionRepository = contributionSubmissionRepository;
        this.contributionApiClient = contributionApiClient;
    }

    @Scheduled(fixedDelayString = "${vork.contributions.promotion-interval-ms:3600000}")
    public void scheduledReconcileLifecycleStatuses() {
        PromotionSummary summary = reconcileLifecycleStatuses();
        log.info("Contribution promotion pass complete [agentsToStaged={}, agentsToPublished={}, jobsToStaged={}, jobsToPublished={}, surfacesToStaged={}, surfacesToPublished={}, skillsToStaged={}, skillsToPublished={}, reflectionsToStaged={}, reflectionsToPublished={}, oauthTemplatesToStaged={}, oauthTemplatesToPublished={}]",
                summary.agentsPromotedToStaged(),
                summary.agentsPromotedToPublished(),
                summary.jobsPromotedToStaged(),
                summary.jobsPromotedToPublished(),
                summary.surfacesPromotedToStaged(),
            summary.surfacesPromotedToPublished(),
            summary.skillsPromotedToStaged(),
            summary.skillsPromotedToPublished(),
            summary.reflectionsPromotedToStaged(),
            summary.reflectionsPromotedToPublished(),
            summary.oauthTemplatesPromotedToStaged(),
            summary.oauthTemplatesPromotedToPublished());
    }

    public PromotionSummary reconcileLifecycleStatuses() {
        log.debug("ENTER reconcileLifecycleStatuses");

        AtomicInteger agentsToStaged = new AtomicInteger();
        AtomicInteger agentsToPublished = new AtomicInteger();
        AtomicInteger agentsToRejected = new AtomicInteger();
        AtomicInteger jobsToStaged = new AtomicInteger();
        AtomicInteger jobsToPublished = new AtomicInteger();
        AtomicInteger jobsToRejected = new AtomicInteger();
        AtomicInteger surfacesToStaged = new AtomicInteger();
        AtomicInteger surfacesToPublished = new AtomicInteger();
        AtomicInteger surfacesToRejected = new AtomicInteger();
        AtomicInteger skillsToStaged = new AtomicInteger();
        AtomicInteger skillsToPublished = new AtomicInteger();
        AtomicInteger skillsToRejected = new AtomicInteger();
        AtomicInteger reflectionsToStaged = new AtomicInteger();
        AtomicInteger reflectionsToPublished = new AtomicInteger();
        AtomicInteger reflectionsToRejected = new AtomicInteger();
        AtomicInteger oauthTemplatesToStaged = new AtomicInteger();
        AtomicInteger oauthTemplatesToPublished = new AtomicInteger();
        AtomicInteger oauthTemplatesToRejected = new AtomicInteger();

        processPaged(agentRepository, agent -> {
            if (agent == null || agent.systemAgent() || agent.groupId() == null || agent.artifactId() == null || agent.version() == null) {
                return;
            }
            String jsonPath = "agents/" + agent.groupId() + "/" + agent.artifactId() + "/" + agent.version() + "/agent.json";

            if (agent.artifactStatus() == sh.vork.ai.agent.ArtifactStatus.SUBMITTED) {
                if (isRejectedPr("agent", agent.uuid())) {
                    AgentTemplate rejected = new AgentTemplate(
                            agent.uuid(),
                            agent.name(),
                            agent.systemPrompt(),
                            agent.allowedTools(),
                            false,
                            agent.skillUuids(),
                            agent.agentType(),
                            agent.bindingUuids(),
                            agent.assignedUsernames(),
                            agent.jobUuids(),
                            agent.recommendedModel(),
                            agent.groupId(),
                            agent.artifactId(),
                            agent.version(),
                            sh.vork.ai.agent.ArtifactStatus.REJECTED);
                    agentRepository.save(rejected);
                    agentsToRejected.incrementAndGet();
                    log.info("Agent marked REJECTED after PR closed without merge [id={}]", agent.uuid());
                    return;
                }
                if (safePathExists(STAGING_BRANCH, jsonPath)) {
                    AgentTemplate staged = new AgentTemplate(
                            agent.uuid(),
                            agent.name(),
                            agent.systemPrompt(),
                            agent.allowedTools(),
                            false,
                            agent.skillUuids(),
                            agent.agentType(),
                            agent.bindingUuids(),
                            agent.assignedUsernames(),
                            agent.jobUuids(),
                            agent.recommendedModel(),
                            agent.groupId(),
                            agent.artifactId(),
                            agent.version(),
                            sh.vork.ai.agent.ArtifactStatus.STAGED);
                    agentRepository.save(staged);
                    agentsToStaged.incrementAndGet();
                    log.debug("Step 1: agent promoted to STAGED [id={}]", agent.uuid());
                }
                return;
            }

            if (agent.artifactStatus() == sh.vork.ai.agent.ArtifactStatus.STAGED
                    && safePathExists(MAIN_BRANCH, jsonPath)) {
                AgentTemplate published = new AgentTemplate(
                        agent.uuid(),
                        agent.name(),
                        agent.systemPrompt(),
                        agent.allowedTools(),
                        false,
                        agent.skillUuids(),
                        agent.agentType(),
                        agent.bindingUuids(),
                        agent.assignedUsernames(),
                        agent.jobUuids(),
                        agent.recommendedModel(),
                        agent.groupId(),
                        agent.artifactId(),
                        agent.version(),
                        sh.vork.ai.agent.ArtifactStatus.PUBLISHED);
                agentRepository.save(published);
                agentsToPublished.incrementAndGet();
                log.debug("Step 2: agent promoted to PUBLISHED [id={}]", agent.uuid());
            }
        });

        processPaged(jobRepository, job -> {
            if (job == null || job.groupId() == null || job.artifactId() == null || job.version() == null) {
                return;
            }
            String jsonPath = "jobs/" + job.groupId() + "/" + job.artifactId() + "/" + job.version() + "/job.json";

            if (job.artifactStatus() == sh.vork.scheduling.domain.ArtifactStatus.SUBMITTED) {
                if (isRejectedPr("job", job.id())) {
                    ScheduledJob rejected = new ScheduledJob(
                            job.id(),
                            job.name(),
                            job.aiPrompt(),
                            job.sessionUuid(),
                            job.userId(),
                            job.invocationType(),
                            job.startTime(),
                            job.repeatDuration(),
                            job.durationType(),
                            job.lastExecutionTime(),
                            job.nextExecutionTime(),
                            job.agentTemplateId(),
                            job.provider(),
                            job.modelId(),
                            job.oobTimeoutMinutes(),
                            job.expectedOutput(),
                            job.status(),
                            job.skillUuids(),
                            job.toolIds(),
                            job.notificationUserIds(),
                            job.groupId(),
                            job.artifactId(),
                            job.version(),
                            sh.vork.scheduling.domain.ArtifactStatus.REJECTED);
                    jobRepository.save(rejected);
                    jobsToRejected.incrementAndGet();
                    log.info("Job marked REJECTED after PR closed without merge [id={}]", job.id());
                    return;
                }
                if (safePathExists(STAGING_BRANCH, jsonPath)) {
                    ScheduledJob staged = new ScheduledJob(
                            job.id(),
                            job.name(),
                            job.aiPrompt(),
                            job.sessionUuid(),
                            job.userId(),
                            job.invocationType(),
                            job.startTime(),
                            job.repeatDuration(),
                            job.durationType(),
                            job.lastExecutionTime(),
                            job.nextExecutionTime(),
                            job.agentTemplateId(),
                            job.provider(),
                            job.modelId(),
                            job.oobTimeoutMinutes(),
                            job.expectedOutput(),
                            job.status(),
                            job.skillUuids(),
                            job.toolIds(),
                            job.notificationUserIds(),
                            job.groupId(),
                            job.artifactId(),
                            job.version(),
                            sh.vork.scheduling.domain.ArtifactStatus.STAGED);
                    jobRepository.save(staged);
                    jobsToStaged.incrementAndGet();
                    log.debug("Step 3: job promoted to STAGED [id={}]", job.id());
                }
                return;
            }

            if (job.artifactStatus() == sh.vork.scheduling.domain.ArtifactStatus.STAGED
                    && safePathExists(MAIN_BRANCH, jsonPath)) {
                ScheduledJob published = new ScheduledJob(
                        job.id(),
                        job.name(),
                        job.aiPrompt(),
                        job.sessionUuid(),
                        job.userId(),
                        job.invocationType(),
                        job.startTime(),
                        job.repeatDuration(),
                        job.durationType(),
                        job.lastExecutionTime(),
                        job.nextExecutionTime(),
                        job.agentTemplateId(),
                        job.provider(),
                        job.modelId(),
                        job.oobTimeoutMinutes(),
                        job.expectedOutput(),
                        job.status(),
                        job.skillUuids(),
                        job.toolIds(),
                        job.notificationUserIds(),
                        job.groupId(),
                        job.artifactId(),
                        job.version(),
                        sh.vork.scheduling.domain.ArtifactStatus.PUBLISHED);
                jobRepository.save(published);
                jobsToPublished.incrementAndGet();
                log.debug("Step 4: job promoted to PUBLISHED [id={}]", job.id());
            }
        });

        processPaged(surfaceRepository, surface -> {
            if (surface == null || surface.groupId() == null || surface.artifactId() == null || surface.version() == null) {
                return;
            }
            String jsonPath = "surfaces/" + surface.groupId() + "/" + surface.artifactId() + "/" + surface.version() + "/surface.json";

            if (surface.artifactStatus() == sh.vork.surface.ArtifactStatus.SUBMITTED) {
                if (isRejectedPr("surface", surface.uuid())) {
                    Surface rejected = new Surface(
                            surface.uuid(),
                            surface.toolId(),
                            surface.name(),
                            surface.description(),
                            surface.sessionUuid(),
                            surface.executionSessionUuid(),
                            surface.skillUuids(),
                            surface.reflectionBindingUuids(),
                            surface.jobUuids(),
                            surface.published(),
                            surface.logoDataUrl(),
                            surface.assignedUserUuids(),
                            surface.accessPolicy(),
                            surface.groupId(),
                            surface.artifactId(),
                            surface.version(),
                            sh.vork.surface.ArtifactStatus.REJECTED,
                            surface.createdAt(),
                            System.currentTimeMillis());
                    surfaceRepository.save(rejected);
                    surfacesToRejected.incrementAndGet();
                    log.info("Surface marked REJECTED after PR closed without merge [id={}]", surface.uuid());
                    return;
                }
                if (safePathExists(STAGING_BRANCH, jsonPath)) {
                    Surface staged = new Surface(
                            surface.uuid(),
                            surface.toolId(),
                            surface.name(),
                            surface.description(),
                            surface.sessionUuid(),
                            surface.executionSessionUuid(),
                            surface.skillUuids(),
                            surface.reflectionBindingUuids(),
                            surface.jobUuids(),
                            surface.published(),
                            surface.logoDataUrl(),
                            surface.assignedUserUuids(),
                            surface.accessPolicy(),
                            surface.groupId(),
                            surface.artifactId(),
                            surface.version(),
                            sh.vork.surface.ArtifactStatus.STAGED,
                            surface.createdAt(),
                            System.currentTimeMillis());
                    surfaceRepository.save(staged);
                    surfacesToStaged.incrementAndGet();
                    log.debug("Step 5: surface promoted to STAGED [id={}]", surface.uuid());
                }
                return;
            }

            if (surface.artifactStatus() == sh.vork.surface.ArtifactStatus.STAGED
                    && safePathExists(MAIN_BRANCH, jsonPath)) {
                Surface published = new Surface(
                        surface.uuid(),
                        surface.toolId(),
                        surface.name(),
                        surface.description(),
                        surface.sessionUuid(),
                        surface.executionSessionUuid(),
                        surface.skillUuids(),
                        surface.reflectionBindingUuids(),
                        surface.jobUuids(),
                        surface.published(),
                        surface.logoDataUrl(),
                        surface.assignedUserUuids(),
                        surface.accessPolicy(),
                        surface.groupId(),
                        surface.artifactId(),
                        surface.version(),
                        sh.vork.surface.ArtifactStatus.PUBLISHED,
                        surface.createdAt(),
                        System.currentTimeMillis());
                surfaceRepository.save(published);
                surfacesToPublished.incrementAndGet();
                log.debug("Step 6: surface promoted to PUBLISHED [id={}]", surface.uuid());
            }
        });

        processPaged(skillGroupRepository, group -> {
            if (group == null || group.groupId() == null || group.artifactId() == null || group.version() == null) {
                return;
            }
            String jsonPath = "skills/" + group.groupId() + "/" + group.artifactId() + "/" + group.version() + "/skills.json";

            if (group.artifactStatus() == sh.vork.skill.ArtifactStatus.SUBMITTED) {
                if (isRejectedPr("skill", group.uuid())) {
                    SkillGroup rejected = new SkillGroup(
                            group.uuid(),
                            group.name(),
                            group.author(),
                            group.category(),
                            group.skills(),
                            group.groupId(),
                            group.artifactId(),
                            group.version(),
                            sh.vork.skill.ArtifactStatus.REJECTED,
                            group.createdAt(),
                            System.currentTimeMillis());
                    skillGroupRepository.save(rejected);
                    skillsToRejected.incrementAndGet();
                    log.info("Skill group marked REJECTED after PR closed without merge [id={}]", group.uuid());
                    return;
                }
                if (safePathExists(STAGING_BRANCH, jsonPath)) {
                    SkillGroup staged = new SkillGroup(
                            group.uuid(),
                            group.name(),
                            group.author(),
                            group.category(),
                            group.skills(),
                            group.groupId(),
                            group.artifactId(),
                            group.version(),
                            sh.vork.skill.ArtifactStatus.STAGED,
                            group.createdAt(),
                            System.currentTimeMillis());
                    skillGroupRepository.save(staged);
                    skillsToStaged.incrementAndGet();
                    log.debug("Step 7: skill group promoted to STAGED [id={}]", group.uuid());
                }
                return;
            }

            if (group.artifactStatus() == sh.vork.skill.ArtifactStatus.STAGED
                    && safePathExists(MAIN_BRANCH, jsonPath)) {
                SkillGroup published = new SkillGroup(
                        group.uuid(),
                        group.name(),
                        group.author(),
                        group.category(),
                        group.skills(),
                        group.groupId(),
                        group.artifactId(),
                        group.version(),
                        sh.vork.skill.ArtifactStatus.PUBLISHED,
                        group.createdAt(),
                        System.currentTimeMillis());
                skillGroupRepository.save(published);
                skillsToPublished.incrementAndGet();
                log.debug("Step 8: skill group promoted to PUBLISHED [id={}]", group.uuid());
            }
        });

        processPaged(reflectionGroupRepository, group -> {
            if (group == null || group.groupId() == null || group.artifactId() == null || group.version() == null) {
                return;
            }
            String jsonPath = "reflections/" + group.groupId() + "/" + group.artifactId() + "/" + group.version() + "/reflections.json";

            if (group.artifactStatus() == sh.vork.reflection.ArtifactStatus.SUBMITTED) {
                if (isRejectedPr("reflection", group.uuid())) {
                    ReflectionGroup rejected = new ReflectionGroup(
                            group.uuid(),
                            group.toolId(),
                            group.name(),
                            group.description(),
                            group.type(),
                            group.baseUrl(),
                            group.urlOverrideEnabled(),
                            group.bindingSecrets(),
                            group.bindingParameters(),
                            group.authenticationMode(),
                            group.oauthTemplateId(),
                            group.groupId(),
                            group.artifactId(),
                            group.version(),
                            sh.vork.reflection.ArtifactStatus.REJECTED,
                            group.createdAt(),
                            System.currentTimeMillis());
                    reflectionGroupRepository.save(rejected);
                    reflectionsToRejected.incrementAndGet();
                    log.info("Reflection group marked REJECTED after PR closed without merge [id={}]", group.uuid());
                    return;
                }
                if (safePathExists(STAGING_BRANCH, jsonPath)) {
                    ReflectionGroup staged = new ReflectionGroup(
                            group.uuid(),
                            group.toolId(),
                            group.name(),
                            group.description(),
                            group.type(),
                            group.baseUrl(),
                            group.urlOverrideEnabled(),
                            group.bindingSecrets(),
                            group.bindingParameters(),
                            group.authenticationMode(),
                            group.oauthTemplateId(),
                            group.groupId(),
                            group.artifactId(),
                            group.version(),
                            sh.vork.reflection.ArtifactStatus.STAGED,
                            group.createdAt(),
                            System.currentTimeMillis());
                    reflectionGroupRepository.save(staged);
                    reflectionsToStaged.incrementAndGet();
                    log.debug("Step 9: reflection group promoted to STAGED [id={}]", group.uuid());
                }
                return;
            }

            if (group.artifactStatus() == sh.vork.reflection.ArtifactStatus.STAGED
                    && safePathExists(MAIN_BRANCH, jsonPath)) {
                ReflectionGroup published = new ReflectionGroup(
                        group.uuid(),
                        group.toolId(),
                        group.name(),
                        group.description(),
                        group.type(),
                        group.baseUrl(),
                        group.urlOverrideEnabled(),
                        group.bindingSecrets(),
                        group.bindingParameters(),
                        group.authenticationMode(),
                        group.oauthTemplateId(),
                        group.groupId(),
                        group.artifactId(),
                        group.version(),
                        sh.vork.reflection.ArtifactStatus.PUBLISHED,
                        group.createdAt(),
                        System.currentTimeMillis());
                reflectionGroupRepository.save(published);
                reflectionsToPublished.incrementAndGet();
                log.debug("Step 10: reflection group promoted to PUBLISHED [id={}]", group.uuid());
            }
        });

        processPaged(oauthTemplateRepository, template -> {
            if (template == null || template.clientName() == null || template.clientName().isBlank()) {
                return;
            }
            String jsonPath = "oauth-templates/" + template.clientName() + ".json";

            if (template.artifactStatus() == sh.vork.oauth.ArtifactStatus.SUBMITTED) {
                if (isRejectedPr("oauth-template", template.uuid())) {
                    OAuthTemplateEntity rejected = new OAuthTemplateEntity(
                            template.uuid(),
                            template.name(),
                            template.clientName(),
                            template.description(),
                            template.authorizeEndpoint(),
                            template.tokenEndpoint(),
                            template.scopes(),
                            template.authorizationParameters(),
                            sh.vork.oauth.ArtifactStatus.REJECTED,
                            template.createdAt(),
                            System.currentTimeMillis());
                    oauthTemplateRepository.save(rejected);
                    oauthTemplatesToRejected.incrementAndGet();
                    log.info("OAuth template marked REJECTED after PR closed without merge [id={}]", template.uuid());
                    return;
                }
                if (safePathExists(MAIN_BRANCH, jsonPath)) {
                    OAuthTemplateEntity published = new OAuthTemplateEntity(
                            template.uuid(),
                            template.name(),
                            template.clientName(),
                            template.description(),
                            template.authorizeEndpoint(),
                            template.tokenEndpoint(),
                            template.scopes(),
                            template.authorizationParameters(),
                            sh.vork.oauth.ArtifactStatus.PUBLISHED,
                            template.createdAt(),
                            System.currentTimeMillis());
                    oauthTemplateRepository.save(published);
                    oauthTemplatesToPublished.incrementAndGet();
                    log.debug("Step 11: oauth template promoted to PUBLISHED [id={}]", template.uuid());
                    return;
                }
                if (safePathExists(STAGING_BRANCH, jsonPath)) {
                    OAuthTemplateEntity staged = new OAuthTemplateEntity(
                            template.uuid(),
                            template.name(),
                            template.clientName(),
                            template.description(),
                            template.authorizeEndpoint(),
                            template.tokenEndpoint(),
                            template.scopes(),
                            template.authorizationParameters(),
                            sh.vork.oauth.ArtifactStatus.STAGED,
                            template.createdAt(),
                            System.currentTimeMillis());
                    oauthTemplateRepository.save(staged);
                    oauthTemplatesToStaged.incrementAndGet();
                    log.debug("Step 12: oauth template promoted to STAGED [id={}]", template.uuid());
                }
                return;
            }

            if (template.artifactStatus() == sh.vork.oauth.ArtifactStatus.STAGED
                    && safePathExists(MAIN_BRANCH, jsonPath)) {
                OAuthTemplateEntity published = new OAuthTemplateEntity(
                        template.uuid(),
                        template.name(),
                        template.clientName(),
                        template.description(),
                        template.authorizeEndpoint(),
                        template.tokenEndpoint(),
                        template.scopes(),
                        template.authorizationParameters(),
                        sh.vork.oauth.ArtifactStatus.PUBLISHED,
                        template.createdAt(),
                        System.currentTimeMillis());
                oauthTemplateRepository.save(published);
                oauthTemplatesToPublished.incrementAndGet();
                log.debug("Step 13: oauth template promoted to PUBLISHED [id={}]", template.uuid());
            }
        });

        PromotionSummary summary = new PromotionSummary(
                agentsToStaged.get(),
                agentsToPublished.get(),
                agentsToRejected.get(),
                jobsToStaged.get(),
                jobsToPublished.get(),
                jobsToRejected.get(),
                surfacesToStaged.get(),
                surfacesToPublished.get(),
                surfacesToRejected.get(),
                skillsToStaged.get(),
                skillsToPublished.get(),
                skillsToRejected.get(),
                reflectionsToStaged.get(),
                reflectionsToPublished.get(),
                reflectionsToRejected.get(),
                oauthTemplatesToStaged.get(),
                oauthTemplatesToPublished.get(),
                oauthTemplatesToRejected.get());
        log.debug("EXIT reconcileLifecycleStatuses: summary={}", summary);
        return summary;
    }

    private boolean isRejectedPr(String artifactType, String artifactUuid) {
        ContributionSubmission submission = contributionSubmissionRepository.get(submissionKey(artifactType, artifactUuid));
        if (submission == null) {
            return false;
        }
        try {
            GitHubContributionApiClient.PullRequestStatus status = contributionApiClient.getPullRequestStatus(
                    submission.upstreamOwner(),
                    submission.upstreamRepository(),
                    submission.pullRequestNumber());
            return status == GitHubContributionApiClient.PullRequestStatus.CLOSED_UNMERGED;
        } catch (RuntimeException ex) {
            log.warn("PR status check failed during contribution reconciliation [artifactType={}, artifactUuid={}, prNumber={}]: {}",
                    artifactType, artifactUuid, submission.pullRequestNumber(), ex.getMessage());
            return false;
        }
    }

    private boolean safePathExists(String branch, String path) {
        try {
            return contributionApiClient.pathExistsInBranch(
                    OFFICIAL_OWNER,
                    OFFICIAL_REPOSITORY,
                    branch,
                    path);
        } catch (RuntimeException ex) {
            log.warn("Contribution promotion path check failed [branch={}, path={}]: {}", branch, path, ex.getMessage());
            return false;
        }
    }

    private static <T extends DatabaseEntity> void processPaged(DatabaseRepository<T> repository,
                                                                java.util.function.Consumer<T> consumer) {
        long total = repository.count();
        int pages = (int) ((total + PAGE_SIZE - 1) / PAGE_SIZE);
        for (int page = 0; page < pages; page++) {
            try (Stream<T> stream = repository.list(page, PAGE_SIZE)) {
                stream.forEach(consumer);
            }
        }
    }

    private static String submissionKey(String artifactType, String artifactUuid) {
        return artifactType + ":" + artifactUuid;
    }

    public record PromotionSummary(int agentsPromotedToStaged,
                                   int agentsPromotedToPublished,
                                   int agentsPromotedToRejected,
                                   int jobsPromotedToStaged,
                                   int jobsPromotedToPublished,
                                   int jobsPromotedToRejected,
                                   int surfacesPromotedToStaged,
                                   int surfacesPromotedToPublished,
                                   int surfacesPromotedToRejected,
                                   int skillsPromotedToStaged,
                                   int skillsPromotedToPublished,
                                   int skillsPromotedToRejected,
                                   int reflectionsPromotedToStaged,
                                   int reflectionsPromotedToPublished,
                                   int reflectionsPromotedToRejected,
                                   int oauthTemplatesPromotedToStaged,
                                   int oauthTemplatesPromotedToPublished,
                                   int oauthTemplatesPromotedToRejected) {
    }
}
