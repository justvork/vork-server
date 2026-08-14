package sh.vork.github.contribution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.oauth.OAuthTemplateEntity;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillGroup;
import sh.vork.surface.Surface;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates publish-time dependency readiness for contribution artifacts.
 *
 * <p>Current policy: every traversed dependency artifact must have status STAGED or PUBLISHED.
 */
@Service
public class ContributionDependencyValidator {

    private static final Logger log = LoggerFactory.getLogger(ContributionDependencyValidator.class);

    private final DatabaseRepository<AgentTemplate> agentRepository;
    private final DatabaseRepository<ScheduledJob> jobRepository;
    private final DatabaseRepository<Surface> surfaceRepository;
    private final DatabaseRepository<SkillGroup> skillGroupRepository;
    private final DatabaseRepository<Skill> skillRepository;
    private final DatabaseRepository<ReflectionGroup> reflectionGroupRepository;
    private final DatabaseRepository<ReflectionBinding> reflectionBindingRepository;
    private final DatabaseRepository<OAuthTemplateEntity> oauthTemplateRepository;

    public ContributionDependencyValidator(DatabaseRepository<AgentTemplate> agentRepository,
                                           DatabaseRepository<ScheduledJob> jobRepository,
                                           DatabaseRepository<Surface> surfaceRepository,
                                           DatabaseRepository<SkillGroup> skillGroupRepository,
                                           DatabaseRepository<Skill> skillRepository,
                                           DatabaseRepository<ReflectionGroup> reflectionGroupRepository,
                                           DatabaseRepository<ReflectionBinding> reflectionBindingRepository,
                                           DatabaseRepository<OAuthTemplateEntity> oauthTemplateRepository) {
        this.agentRepository = agentRepository;
        this.jobRepository = jobRepository;
        this.surfaceRepository = surfaceRepository;
        this.skillGroupRepository = skillGroupRepository;
        this.skillRepository = skillRepository;
        this.reflectionGroupRepository = reflectionGroupRepository;
        this.reflectionBindingRepository = reflectionBindingRepository;
        this.oauthTemplateRepository = oauthTemplateRepository;
    }

    public DependencyValidationReport validateAgent(String agentId) {
        log.debug("ENTER validateAgent: agentId={}", agentId);
        ValidationContext ctx = new ValidationContext("agent", agentId);
        AgentTemplate root = agentRepository.get(agentId);
        if (root == null) {
            ctx.fail("agent", agentId, "", "MISSING", pathFor(ctx, "agent", agentId), "Agent not found.");
            return ctx.build();
        }
        walkAgent(root, ctx);
        DependencyValidationReport report = ctx.build();
        log.debug("EXIT validateAgent: agentId={}, valid={}, issues={}, checked={}, cycles={}",
                agentId, report.valid(), report.issues().size(), report.checked().size(), report.cycles().size());
        return report;
    }

    public DependencyValidationReport validateJob(String jobId) {
        log.debug("ENTER validateJob: jobId={}", jobId);
        ValidationContext ctx = new ValidationContext("job", jobId);
        ScheduledJob root = jobRepository.get(jobId);
        if (root == null) {
            ctx.fail("job", jobId, "", "MISSING", pathFor(ctx, "job", jobId), "Job not found.");
            return ctx.build();
        }
        walkJob(root, ctx);
        DependencyValidationReport report = ctx.build();
        log.debug("EXIT validateJob: jobId={}, valid={}, issues={}, checked={}, cycles={}",
                jobId, report.valid(), report.issues().size(), report.checked().size(), report.cycles().size());
        return report;
    }

    public DependencyValidationReport validateSurface(String surfaceId) {
        log.debug("ENTER validateSurface: surfaceId={}", surfaceId);
        ValidationContext ctx = new ValidationContext("surface", surfaceId);
        Surface root = surfaceRepository.get(surfaceId);
        if (root == null) {
            ctx.fail("surface", surfaceId, "", "MISSING", pathFor(ctx, "surface", surfaceId), "Surface not found.");
            return ctx.build();
        }
        walkSurface(root, ctx);
        DependencyValidationReport report = ctx.build();
        log.debug("EXIT validateSurface: surfaceId={}, valid={}, issues={}, checked={}, cycles={}",
                surfaceId, report.valid(), report.issues().size(), report.checked().size(), report.cycles().size());
        return report;
    }

    public DependencyValidationReport validateSkillGroup(String skillGroupId) {
        log.debug("ENTER validateSkillGroup: skillGroupId={}", skillGroupId);
        ValidationContext ctx = new ValidationContext("skill-group", skillGroupId);
        SkillGroup root = skillGroupRepository.get(skillGroupId);
        if (root == null) {
            ctx.fail("skill-group", skillGroupId, "", "MISSING", pathFor(ctx, "skill-group", skillGroupId), "Skill group not found.");
            return ctx.build();
        }
        walkSkillGroup(root, ctx);
        DependencyValidationReport report = ctx.build();
        log.debug("EXIT validateSkillGroup: skillGroupId={}, valid={}, issues={}, checked={}, cycles={}",
                skillGroupId, report.valid(), report.issues().size(), report.checked().size(), report.cycles().size());
        return report;
    }

    public DependencyValidationReport validateReflectionGroup(String reflectionGroupId) {
        log.debug("ENTER validateReflectionGroup: reflectionGroupId={}", reflectionGroupId);
        ValidationContext ctx = new ValidationContext("reflection-group", reflectionGroupId);
        ReflectionGroup root = reflectionGroupRepository.get(reflectionGroupId);
        if (root == null) {
            ctx.fail("reflection-group", reflectionGroupId, "", "MISSING", pathFor(ctx, "reflection-group", reflectionGroupId), "Reflection group not found.");
            return ctx.build();
        }
        walkReflectionGroup(root, ctx);
        DependencyValidationReport report = ctx.build();
        log.debug("EXIT validateReflectionGroup: reflectionGroupId={}, valid={}, issues={}, checked={}, cycles={}",
                reflectionGroupId, report.valid(), report.issues().size(), report.checked().size(), report.cycles().size());
        return report;
    }

    private void walkAgent(AgentTemplate agent, ValidationContext ctx) {
        String key = nodeKey("agent", agent.uuid());
        if (!ctx.enter(key)) {
            return;
        }
        try {
            for (String skillUuid : safeList(agent.skillUuids())) {
                walkSkillByUuid(skillUuid, ctx);
            }
            for (String bindingUuid : safeList(agent.bindingUuids())) {
                walkBindingByUuid(bindingUuid, ctx);
            }
        } finally {
            ctx.exit(key);
        }
    }

    private void walkJob(ScheduledJob job, ValidationContext ctx) {
        String key = nodeKey("job", job.id());
        if (!ctx.enter(key)) {
            return;
        }
        try {
            if (job.agentTemplateId() != null && !job.agentTemplateId().isBlank()) {
                String depId = job.agentTemplateId().trim();
                AgentTemplate agent = agentRepository.get(depId);
                if (agent == null) {
                    ctx.fail("agent", depId, "", "MISSING", pathFor(ctx, "agent", depId), "Referenced agent does not exist.");
                } else {
                    ctx.check("agent", depId, agent.name(), String.valueOf(agent.artifactStatus()));
                    ensureStaged("agent", depId, agent.name(), String.valueOf(agent.artifactStatus()), ctx);
                    walkAgent(agent, ctx);
                }
            }
            for (String skillUuid : safeList(job.skillUuids())) {
                walkSkillByUuid(skillUuid, ctx);
            }
        } finally {
            ctx.exit(key);
        }
    }

    private void walkSurface(Surface surface, ValidationContext ctx) {
        String key = nodeKey("surface", surface.uuid());
        if (!ctx.enter(key)) {
            return;
        }
        try {
            for (String skillUuid : safeList(surface.skillUuids())) {
                walkSkillByUuid(skillUuid, ctx);
            }
            for (String bindingUuid : safeList(surface.reflectionBindingUuids())) {
                walkBindingByUuid(bindingUuid, ctx);
            }
            for (String jobUuid : safeList(surface.jobUuids())) {
                ScheduledJob job = jobRepository.get(jobUuid);
                if (job == null) {
                    ctx.fail("job", jobUuid, "", "MISSING", pathFor(ctx, "job", jobUuid), "Referenced job does not exist.");
                    continue;
                }
                ctx.check("job", job.uuid(), job.name(), String.valueOf(job.artifactStatus()));
                ensureStaged("job", job.uuid(), job.name(), String.valueOf(job.artifactStatus()), ctx);
                walkJob(job, ctx);
            }
        } finally {
            ctx.exit(key);
        }
    }

    private void walkSkillGroup(SkillGroup skillGroup, ValidationContext ctx) {
        String key = nodeKey("skill-group", skillGroup.uuid());
        if (!ctx.enter(key)) {
            return;
        }
        try {
            List<Skill> skills = skillsForGroup(skillGroup);
            for (Skill skill : skills) {
                walkSkill(skill, ctx);
            }
        } finally {
            ctx.exit(key);
        }
    }

    private void walkSkillByUuid(String skillUuid, ValidationContext ctx) {
        if (skillUuid == null || skillUuid.isBlank()) {
            return;
        }
        Skill skill = skillRepository.get(skillUuid.trim());
        if (skill == null) {
            ctx.fail("skill", skillUuid.trim(), "", "MISSING", pathFor(ctx, "skill", skillUuid.trim()), "Referenced skill does not exist.");
            return;
        }
        walkSkill(skill, ctx);
    }

    private void walkSkill(Skill skill, ValidationContext ctx) {
        String key = nodeKey("skill", skill.uuid());
        if (!ctx.enter(key)) {
            return;
        }
        try {
            // groupUuid is an ownership/back-reference, not an upstream publish dependency.

            for (String subSkillUuid : safeList(skill.subSkillUuids())) {
                walkSkillByUuid(subSkillUuid, ctx);
            }
            for (String bindingUuid : safeList(skill.bindingUuids())) {
                walkBindingByUuid(bindingUuid, ctx);
            }
        } finally {
            ctx.exit(key);
        }
    }

    private void walkBindingByUuid(String bindingUuid, ValidationContext ctx) {
        if (bindingUuid == null || bindingUuid.isBlank()) {
            return;
        }
        ReflectionBinding binding = reflectionBindingRepository.get(bindingUuid.trim());
        if (binding == null) {
            ctx.fail("reflection-binding", bindingUuid.trim(), "", "MISSING", pathFor(ctx, "reflection-binding", bindingUuid.trim()), "Referenced reflection binding does not exist.");
            return;
        }
        walkBinding(binding, ctx);
    }

    private void walkBinding(ReflectionBinding binding, ValidationContext ctx) {
        String key = nodeKey("reflection-binding", binding.uuid());
        if (!ctx.enter(key)) {
            return;
        }
        try {
            String groupUuid = trimToNull(binding.groupUuid());
            if (groupUuid == null) {
                ctx.fail("reflection-group", "", "", "MISSING", pathFor(ctx, "reflection-binding", binding.uuid()), "Reflection binding has no groupUuid.");
                return;
            }
            ReflectionGroup group = reflectionGroupRepository.get(groupUuid);
            if (group == null) {
                ctx.fail("reflection-group", groupUuid, "", "MISSING", pathFor(ctx, "reflection-group", groupUuid), "Owning reflection group does not exist.");
                return;
            }
            ctx.check("reflection-group", group.uuid(), group.name(), String.valueOf(group.artifactStatus()));
            if (!ctx.isRoot("reflection-group", group.uuid())) {
                ensureStaged("reflection-group", group.uuid(), group.name(), String.valueOf(group.artifactStatus()), ctx);
            }
            walkReflectionGroup(group, ctx);
        } finally {
            ctx.exit(key);
        }
    }

    private void walkReflectionGroup(ReflectionGroup group, ValidationContext ctx) {
        String key = nodeKey("reflection-group", group.uuid());
        if (!ctx.enter(key)) {
            return;
        }
        try {
            String oauthTemplateId = trimToNull(group.oauthTemplateId());
            if (oauthTemplateId == null) {
                return;
            }
            OAuthTemplateEntity oauthTemplate = oauthTemplateRepository.get(oauthTemplateId);
            if (oauthTemplate == null) {
                ctx.fail("oauth-template", oauthTemplateId, "", "MISSING", pathFor(ctx, "oauth-template", oauthTemplateId), "Referenced OAuth template does not exist.");
                return;
            }
            String status = String.valueOf(oauthTemplate.artifactStatus());
            ctx.check("oauth-template", oauthTemplate.uuid(), oauthTemplate.name(), status);
            ensureStaged("oauth-template", oauthTemplate.uuid(), oauthTemplate.name(), status, ctx);
        } finally {
            ctx.exit(key);
        }
    }

    private static void ensureStaged(String componentType, String componentId, String status, ValidationContext ctx) {
        ensureStaged(componentType, componentId, "", status, ctx);
    }

    private static void ensureStaged(String componentType, String componentId, String componentName, String status, ValidationContext ctx) {
        if (!isPublishReadyStatus(status)) {
            ctx.fail(componentType, componentId, componentName, status,
                    pathFor(ctx, componentType, componentId),
                    "Dependency status must be STAGED or PUBLISHED before PR generation.");
        }
    }

    private static boolean isPublishReadyStatus(String status) {
        return "STAGED".equalsIgnoreCase(status) || "PUBLISHED".equalsIgnoreCase(status);
    }

    private List<Skill> skillsForGroup(SkillGroup group) {
        if (group == null) {
            return List.of();
        }
        if (group.skills() != null && !group.skills().isEmpty()) {
            return group.skills();
        }
        String groupUuid = trimToNull(group.uuid());
        if (groupUuid == null) {
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

    private static List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(String::trim)
                .toList();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nodeKey(String type, String id) {
        return type + ":" + (id == null ? "" : id);
    }

    private static String pathFor(ValidationContext ctx, String leafType, String leafId) {
        List<String> nodes = new ArrayList<>(ctx.stack());
        String leaf = leafType + ":" + (leafId == null ? "" : leafId);
        if (nodes.isEmpty() || !leaf.equals(nodes.get(nodes.size() - 1))) {
            nodes.add(leaf);
        }
        return String.join(" -> ", nodes);
    }

    private static String buildHumanSummary(DependencyValidationReport report) {
        if (report.valid()) {
            return "All dependencies are publish-ready (STAGED or PUBLISHED).";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Dependency validation failed for ")
                .append(report.rootType())
                .append(" ")
                .append(report.rootId())
                .append(". ")
                .append(report.issues().size())
                .append(" blocking issue(s) found.\n");
        int limit = Math.min(10, report.issues().size());
        for (int i = 0; i < limit; i++) {
            DependencyIssue issue = report.issues().get(i);
            sb.append(i + 1)
                    .append(". ")
                    .append(issue.componentType())
                    .append(" ")
                    .append(displayNameOrId(issue.componentName(), issue.componentId()))
                    .append(" status=")
                    .append(issue.status())
                    .append(" at ")
                    .append(humanizePath(issue.path(), report))
                    .append("\n");
        }
        if (report.issues().size() > limit) {
            sb.append("... and ")
                    .append(report.issues().size() - limit)
                    .append(" more issue(s).");
        }
        if (!report.cycles().isEmpty()) {
            sb.append("\nCycle(s) detected and safely truncated: ")
                    .append(report.cycles().size());
        }
        return sb.toString().trim();
    }

    private static String displayNameOrId(String name, String id) {
        String normalizedName = name == null ? "" : name.trim();
        if (!normalizedName.isEmpty()) {
            return normalizedName;
        }
        return id == null ? "" : id.trim();
    }

    private static String humanizePath(String path, DependencyValidationReport report) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String[] nodes = path.split("\\s*->\\s*");
        List<String> pretty = new ArrayList<>();
        for (String node : nodes) {
            String[] parts = node.split(":", 2);
            String type = parts.length > 0 ? parts[0].trim() : "";
            String id = parts.length > 1 ? parts[1].trim() : "";
            String name = findComponentName(type, id, report);
            String label = displayNameOrId(name, id);
            pretty.add(type + ":" + label);
        }
        return String.join(" -> ", pretty);
    }

    private static String findComponentName(String type, String id, DependencyValidationReport report) {
        if (report == null) {
            return "";
        }
        for (DependencyCheck checked : report.checked()) {
            if (checked.componentType().equals(type) && checked.componentId().equals(id)) {
                return checked.componentName();
            }
        }
        for (DependencyIssue issue : report.issues()) {
            if (issue.componentType().equals(type) && issue.componentId().equals(id)) {
                return issue.componentName();
            }
        }
        return "";
    }

    public record DependencyValidationReport(String rootType,
                                             String rootId,
                                             boolean valid,
                                             String summary,
                                             List<DependencyCheck> checked,
                                             List<DependencyIssue> issues,
                                             List<String> cycles) {
    }

    public record DependencyCheck(String componentType,
                                  String componentId,
                                  String componentName,
                                  String status) {
    }

    public record DependencyIssue(String componentType,
                                  String componentId,
                                  String componentName,
                                  String status,
                                  String path,
                                  String reason) {
    }

    private static final class ValidationContext {
        private final String rootType;
        private final String rootId;
        private final List<DependencyCheck> checked = new ArrayList<>();
        private final List<DependencyIssue> issues = new ArrayList<>();
        private final List<String> cycles = new ArrayList<>();
        private final Set<String> visited = new LinkedHashSet<>();
        private final Set<String> onStack = new LinkedHashSet<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> issueDedup = new LinkedHashSet<>();

        private ValidationContext(String rootType, String rootId) {
            this.rootType = normalize(rootType);
            this.rootId = normalize(rootId);
        }

        boolean enter(String key) {
            if (onStack.contains(key)) {
                cycles.add(String.join(" -> ", stack) + " -> " + key);
                return false;
            }
            if (visited.contains(key)) {
                return false;
            }
            visited.add(key);
            onStack.add(key);
            stack.addLast(key);
            return true;
        }

        void exit(String key) {
            if (!stack.isEmpty() && key.equals(stack.peekLast())) {
                stack.removeLast();
            } else {
                stack.remove(key);
            }
            onStack.remove(key);
        }

        void check(String componentType, String componentId, String status) {
            check(componentType, componentId, "", status);
        }

        void check(String componentType, String componentId, String componentName, String status) {
            checked.add(new DependencyCheck(normalize(componentType), normalize(componentId), normalize(componentName), normalize(status)));
        }

        void fail(String componentType, String componentId, String status, String path, String reason) {
            fail(componentType, componentId, "", status, path, reason);
        }

        void fail(String componentType, String componentId, String componentName, String status, String path, String reason) {
            String type = normalize(componentType);
            String id = normalize(componentId);
            String name = normalize(componentName);
            String normalizedStatus = normalize(status);
            String normalizedPath = normalize(path);
            String normalizedReason = normalize(reason);
            String dedupKey = type + "|" + id + "|" + name + "|" + normalizedStatus + "|" + normalizedPath + "|" + normalizedReason;
            if (issueDedup.add(dedupKey)) {
                issues.add(new DependencyIssue(type, id, name, normalizedStatus, normalizedPath, normalizedReason));
            }
        }

        Deque<String> stack() {
            return stack;
        }

        boolean isRoot(String type, String id) {
            return rootType.equals(normalize(type)) && rootId.equals(normalize(id));
        }

        DependencyValidationReport build() {
            boolean valid = issues.isEmpty();
            List<DependencyCheck> checkedOut = List.copyOf(checked);
            List<DependencyIssue> issuesOut = List.copyOf(issues);
            List<String> cyclesOut = List.copyOf(cycles);
            DependencyValidationReport report = new DependencyValidationReport(
                    rootType,
                    rootId,
                    valid,
                    "",
                    checkedOut,
                    issuesOut,
                    cyclesOut);
            String summary = buildHumanSummary(report);
            return new DependencyValidationReport(rootType, rootId, valid, summary, checkedOut, issuesOut, cyclesOut);
        }

        private static String normalize(String value) {
            if (value == null) {
                return "";
            }
            return value.trim();
        }
    }
}
