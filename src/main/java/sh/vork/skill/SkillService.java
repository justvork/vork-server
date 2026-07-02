package sh.vork.skill;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.orm.DatabaseRepository;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.TypeGeneratorService;

/**
 * CRUD and execution service for {@link Skill} entities and {@link SkillGroup}
 * containers.
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private final DatabaseRepository<Skill> skillRepo;
    private final DatabaseRepository<SkillGroup> skillGroupRepo;
    private final DatabaseRepository<AiSession> aiSessionRepo;

    @Lazy
    @Autowired
    private DatabaseRepository<AgentTemplate> agentTemplateRepo;

    @Lazy
    @Autowired
    private TypeGeneratorService typeGeneratorService;

    @Lazy
    @Autowired
    private DatabaseRepository<JavaType> javaTypeRepository;

    public SkillService(DatabaseRepository<Skill> skillRepo,
                        DatabaseRepository<SkillGroup> skillGroupRepo,
                        DatabaseRepository<AiSession> aiSessionRepo) {
        this.skillRepo = skillRepo;
        this.skillGroupRepo = skillGroupRepo;
        this.aiSessionRepo = aiSessionRepo;
    }

    // -- Group CRUD ----------------------------------------------------------

    public List<SkillGroup> listGroups() {
        log.debug("ENTER listGroups");
        try (var stream = skillGroupRepo.list(0, Integer.MAX_VALUE)) {
            return stream.collect(Collectors.toList());
        }
    }

    public SkillGroup getGroup(String uuid) {
        log.debug("ENTER getGroup: [uuid={}]", uuid);
        return skillGroupRepo.get(uuid);
    }

    public SkillGroup createGroup(SkillGroupRequest req) {
        log.debug("ENTER createGroup: [name={}]", req.name());
        long now = System.currentTimeMillis();
        SkillGroup group = new SkillGroup(
                UUID.randomUUID().toString(),
                req.name(),
                req.author(),
                req.category(),
                List.of(),
                1L,
                now,
                now);
        skillGroupRepo.save(group);
        log.info("Skill group created [uuid={}, name={}]", group.uuid(), group.name());
        return group;
    }

    public SkillGroup updateGroup(String uuid, SkillGroupRequest req) {
        log.debug("ENTER updateGroup: [uuid={}]", uuid);
        SkillGroup existing = skillGroupRepo.get(uuid);
        if (existing == null) {
            return null;
        }

        SkillGroup updated = new SkillGroup(
                uuid,
                req.name(),
                req.author(),
                req.category(),
            existing.skills(),
                existing.version() + 1,
                existing.createdAt(),
                System.currentTimeMillis());
        skillGroupRepo.save(updated);
        log.info("Skill group updated [uuid={}, name={}, version={}]", uuid, updated.name(), updated.version());
        return updated;
    }

    public GroupDeleteResult deleteGroup(String uuid) {
        log.debug("ENTER deleteGroup: [uuid={}]", uuid);
        SkillGroup existing = skillGroupRepo.get(uuid);
        if (existing == null) {
            return new GroupDeleteResult(false, "Group not found.");
        }

        List<Skill> members = skillsForGroup(uuid);
        if (!members.isEmpty()) {
            return new GroupDeleteResult(false, "Cannot delete non-empty group. Remove or move all skills first.");
        }

        skillGroupRepo.delete(uuid);
        log.info("Skill group deleted [uuid={}]", uuid);
        return new GroupDeleteResult(true, null);
    }

    // -- Skill CRUD ----------------------------------------------------------

    public List<Skill> list() {
        log.debug("ENTER list");
        try (var stream = skillRepo.list(0, Integer.MAX_VALUE)) {
            return stream.collect(Collectors.toList());
        }
    }

    public List<Skill> skillsForGroup(String groupUuid) {
        log.debug("ENTER skillsForGroup: [groupUuid={}]", groupUuid);
        if (groupUuid == null || groupUuid.isBlank()) {
            return List.of();
        }
        SkillGroup group = skillGroupRepo.get(groupUuid);
        if (group == null || group.skills() == null) {
            return List.of();
        }
        return group.skills();
    }

    public Skill get(String uuid) {
        log.debug("ENTER get: [uuid={}]", uuid);
        return skillRepo.get(uuid);
    }

    public Skill create(SkillRequest req) {
        log.debug("ENTER create: [name={}]", req.name());
        SkillGroup group = requireGroup(req.groupUuid());
        List<String> missingDependencies = findMissingDependencies(req.subSkillUuids(), Set.of());
        if (!missingDependencies.isEmpty()) {
            throw new IllegalArgumentException("Missing sub-skill dependencies: " + String.join(", ", missingDependencies));
        }

        long now = System.currentTimeMillis();
        Skill skill = new Skill(
                UUID.randomUUID().toString(),
                req.name(),
                req.description(),
                req.groupUuid(),
                req.autoShareWithinGroup(),
                req.parameters() != null ? List.copyOf(req.parameters()) : List.of(),
                req.instructions(),
                req.allowedTools() != null ? List.copyOf(req.allowedTools()) : List.of(),
                req.allowedTypes() != null ? List.copyOf(req.allowedTypes()) : List.of(),
                req.subSkillUuids() != null ? List.copyOf(req.subSkillUuids()) : List.of(),
                1L,
                now,
                now,
                req.secrets() != null ? List.copyOf(req.secrets()) : List.of());
        skillRepo.save(skill);
            syncGroupSkills(group.uuid());
        log.info("Skill created [uuid={}, name={}, group={}]", skill.uuid(), skill.name(), skill.groupUuid());
        return skill;
    }

    public Skill update(String uuid, SkillRequest req) {
        log.debug("ENTER update: [uuid={}]", uuid);
        Skill existing = skillRepo.get(uuid);
        if (existing == null) {
            return null;
        }

        SkillGroup targetGroup = requireGroup(req.groupUuid());
        List<String> missingDependencies = findMissingDependencies(req.subSkillUuids(), Set.of(uuid));
        if (!missingDependencies.isEmpty()) {
            throw new IllegalArgumentException("Missing sub-skill dependencies: " + String.join(", ", missingDependencies));
        }

        Skill updated = new Skill(
                uuid,
                req.name(),
                req.description(),
                req.groupUuid(),
                req.autoShareWithinGroup(),
                req.parameters() != null ? List.copyOf(req.parameters()) : List.of(),
                req.instructions(),
                req.allowedTools() != null ? List.copyOf(req.allowedTools()) : List.of(),
                req.allowedTypes() != null ? List.copyOf(req.allowedTypes()) : List.of(),
                req.subSkillUuids() != null ? List.copyOf(req.subSkillUuids()) : List.of(),
                existing.version() + 1,
                existing.createdAt(),
                System.currentTimeMillis(),
                req.secrets() != null ? List.copyOf(req.secrets()) : List.of());
        skillRepo.save(updated);

        if (!existing.groupUuid().equals(targetGroup.uuid())) {
            syncGroupSkills(existing.groupUuid());
        }
        syncGroupSkills(targetGroup.uuid());

        log.info("Skill updated [uuid={}, name={}, version={}, group={}]", uuid, updated.name(), updated.version(), updated.groupUuid());
        return updated;
    }

    public void delete(String uuid) {
        log.debug("ENTER delete: [uuid={}]", uuid);
        Skill existing = skillRepo.get(uuid);
        String groupUuid = existing != null ? existing.groupUuid() : null;
        skillRepo.delete(uuid);
        if (groupUuid != null && !groupUuid.isBlank()) {
            syncGroupSkills(groupUuid);
        }
        log.info("Skill deleted [uuid={}]", uuid);
    }

    // -- Execution -----------------------------------------------------------

    public String executeSkill(String skillUuid, Map<String, String> parameters) {
        log.debug("ENTER executeSkill: [skillUuid={}, paramKeys={}]", skillUuid,
                parameters == null ? "null" : parameters.keySet());

        Skill skill = skillRepo.get(skillUuid);
        if (skill == null) {
            return "{\"status\":\"error\",\"message\":\"Skill not found: " + skillUuid + "\"}";
        }

        Map<String, String> params = parameters != null ? parameters : Map.of();

        List<String> missing = skill.parameters().stream()
                .filter(p -> {
                    String val = params.get(p.name());
                    return val == null || val.isBlank();
                })
                .map(SkillParameter::name)
                .toList();

        if (!missing.isEmpty()) {
            log.info("Skill invocation missing parameters [skill={}, missing={}]", skillUuid, missing);
            return "{\"status\":\"missing_parameters\"," +
                    "\"missing\":" + toJsonArray(missing) + "," +
                    "\"message\":\"Required parameters missing: " + String.join(", ", missing)
                    + ". Please collect these values from the user and retry.\"}";
        }

        String callerSessionUuid = ToolExecutionContext.getSessionUuid();
        if (callerSessionUuid == null || callerSessionUuid.isBlank()) {
            return "{\"status\":\"error\",\"message\":\"executeSkill must be called from within an active session\"}";
        }
        AiSession callerSession = aiSessionRepo.get(callerSessionUuid);
        if (callerSession == null) {
            return "{\"status\":\"error\",\"message\":\"Caller session not found: " + callerSessionUuid + "\"}";
        }

        String agentId = callerSession.getActiveAgentTemplateId();
        if (agentId != null && !agentId.isBlank()) {
            AgentTemplate template = agentTemplateRepo.get(agentId);
            if (template != null && template.skillUuids() != null && !template.skillUuids().isEmpty()) {
                boolean inAgentSkills = template.skillUuids().contains(skillUuid);
                boolean inSessionSkills = callerSession.sessionSkillUuids() != null
                        && callerSession.sessionSkillUuids().contains(skillUuid);
                if (!inAgentSkills && !inSessionSkills) {
                    log.warn("Skill access denied — skill not assigned to agent or session [session={}, agent={}, skill={}]",
                            callerSessionUuid, agentId, skillUuid);
                    return "{\"status\":\"error\",\"message\":\"Skill '" + skill.name()
                            + "' is not assigned to this agent. Only skills configured for your agent may be executed.\"}";
                }
            }
        }

        String resolvedInstructions = substituteNonSecretParams(skill.instructions(), skill.parameters(), params);
        SkillFrame frame = new SkillFrame(
                skillUuid, skill.name(), resolvedInstructions,
                skill.allowedTools(), skill.allowedTypes(), params,
                callerSession.messages().size() + 1);

        List<SkillFrame> newStack = new ArrayList<>(callerSession.skillStack());
        newStack.add(frame);
        aiSessionRepo.save(new AiSession(
                callerSession.uuid(), callerSession.provider(), callerSession.originMode(),
                callerSession.username(), callerSession.name(), callerSession.createdAt(),
                callerSession.currentRoundCount(), callerSession.messages(),
                callerSession.environmentVariables(), callerSession.status(),
                callerSession.activeAgentTemplateId(), callerSession.modelId(),
                List.copyOf(newStack), callerSession.sessionSkillUuids(), callerSession.sessionToolIds()));

        String initialPrompt = buildInitialPrompt(skill, params);

        log.info("Skill activated [session={}, skill={}, stackDepth={}]",
                callerSessionUuid, skillUuid, newStack.size());

        throw new SkillActivatedException(skillUuid, skill.name(), initialPrompt);
    }

    // -- Sub-skill resolution ------------------------------------------------

    public List<Skill> resolveEffectiveSubSkills(String skillUuid) {
        Skill skill = skillRepo.get(skillUuid);
        if (skill == null) {
            return List.of();
        }
        return resolveEffectiveSubSkills(skill);
    }

    public List<Skill> resolveEffectiveSubSkills(Skill skill) {
        if (skill == null) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>(skill.subSkillUuids());

        if (skill.groupUuid() != null && !skill.groupUuid().isBlank()) {
            List<Skill> peers = skillsForGroup(skill.groupUuid());
            for (Skill peer : peers) {
                if (peer == null || peer.uuid().equals(skill.uuid())) {
                    continue;
                }
                if (peer.autoShareWithinGroup()) {
                    ids.add(peer.uuid());
                }
            }
        }

        List<Skill> resolved = new ArrayList<>();
        for (String id : ids) {
            Skill sub = skillRepo.get(id);
            if (sub != null) {
                resolved.add(sub);
            }
        }
        return List.copyOf(resolved);
    }

    // -- Export / Import -----------------------------------------------------

    public SkillGroupExportPackage exportGroup(String groupUuid) {
        log.debug("ENTER exportGroup: [groupUuid={}]", groupUuid);
        syncGroupSkills(groupUuid);
        SkillGroup group = skillGroupRepo.get(groupUuid);
        if (group == null) {
            return null;
        }

        List<Skill> skills = sortSkills(group.skills() == null ? List.of() : group.skills());
        LinkedHashSet<String> allTypes = new LinkedHashSet<>();
        for (Skill skill : skills) {
            allTypes.addAll(skill.allowedTypes());
        }

        List<SkillExportType> types = new ArrayList<>();
        for (String fqn : allTypes) {
            JavaType jt = javaTypeRepository.get(fqn);
            if (jt != null && jt.source() != null) {
                types.add(new SkillExportType(fqn, jt.source()));
            }
        }

        SkillGroup normalizedGroup = new SkillGroup(
                group.uuid(),
                group.name(),
                group.author(),
                group.category(),
                skills,
                group.version(),
                group.createdAt(),
                group.updatedAt());

        log.debug("EXIT exportGroup: [groupUuid={}, skills={}, embeddedTypes={}]", groupUuid, skills.size(), types.size());
            return new SkillGroupExportPackage("1.0", normalizedGroup, types);
    }

    public SkillGroupImportResult importGroup(SkillGroupExportPackage pkg) {
        log.debug("ENTER importGroup: [groupUuid={}]",
                pkg != null && pkg.group() != null ? pkg.group().uuid() : "null");

        if (pkg == null || pkg.group() == null || pkg.group().skills() == null || pkg.group().skills().isEmpty()) {
            return new SkillGroupImportResult("error", null, List.of(), List.of(), "Invalid skill-group export package.");
        }

        SkillGroup incomingGroup = pkg.group();
        List<Skill> incomingSkills = sortSkills(incomingGroup.skills());
        if (skillGroupRepo.get(incomingGroup.uuid()) != null) {
            return new SkillGroupImportResult(
                    "already_installed",
                    incomingGroup.uuid(),
                    List.of(),
                    List.of(),
                    "Skill group '" + incomingGroup.name() + "' is already installed.");
        }

        List<String> incomingSkillIds = incomingSkills.stream().map(Skill::uuid).toList();
        for (String skillId : incomingSkillIds) {
            if (skillRepo.get(skillId) != null) {
                return new SkillGroupImportResult(
                        "already_installed",
                        incomingGroup.uuid(),
                        List.of(),
                        List.of(),
                        "Skill with UUID '" + skillId + "' is already installed.");
            }
        }

        List<String> missingDependencies = new ArrayList<>();
        Set<String> incomingSet = Set.copyOf(incomingSkillIds);
        for (Skill skill : incomingSkills) {
            if (skill.subSkillUuids() == null || skill.subSkillUuids().isEmpty()) {
                continue;
            }
            for (String subUuid : skill.subSkillUuids()) {
                if (incomingSet.contains(subUuid)) {
                    continue;
                }
                if (skillRepo.get(subUuid) == null) {
                    missingDependencies.add(skill.uuid() + " -> " + subUuid);
                }
            }
        }

        if (!missingDependencies.isEmpty()) {
            return new SkillGroupImportResult(
                    "missing_dependencies",
                    incomingGroup.uuid(),
                    List.of(),
                    List.copyOf(missingDependencies),
                    "Import blocked. Missing sub-skill dependencies were found.");
        }

        List<String> typeErrors = new ArrayList<>();
        if (pkg.types() != null) {
            for (SkillExportType t : pkg.types()) {
                try {
                    typeGeneratorService.compileAndSave(t.source());
                    log.debug("Compiled imported type [fqn={}]", t.fqn());
                } catch (Exception e) {
                    log.warn("Failed to compile type {} during group import: {}", t.fqn(), e.getMessage());
                    typeErrors.add(t.fqn() + ": " + e.getMessage());
                }
            }
        }

        long now = System.currentTimeMillis();
        List<Skill> normalizedSkills = incomingSkills.stream()
            .map(skill -> normalizeImportedSkill(skill, incomingGroup.uuid()))
            .toList();
        SkillGroup normalizedGroup = new SkillGroup(
                incomingGroup.uuid(),
                incomingGroup.name(),
                incomingGroup.author(),
                incomingGroup.category(),
            normalizedSkills,
                incomingGroup.version() < 1 ? 1 : incomingGroup.version(),
                incomingGroup.createdAt() > 0 ? incomingGroup.createdAt() : now,
                incomingGroup.updatedAt() > 0 ? incomingGroup.updatedAt() : now);
        skillGroupRepo.save(normalizedGroup);

        for (Skill normalizedSkill : normalizedSkills) {
            skillRepo.save(normalizedSkill);
        }

        String message = typeErrors.isEmpty()
                ? null
                : "Imported with type compilation errors: " + String.join("; ", typeErrors);

        return new SkillGroupImportResult(
                "imported",
                normalizedGroup.uuid(),
                List.copyOf(incomingSkillIds),
                List.of(),
                message);
    }

    // -- Helpers -------------------------------------------------------------

    private SkillGroup requireGroup(String groupUuid) {
        if (groupUuid == null || groupUuid.isBlank()) {
            throw new IllegalArgumentException("groupUuid is required.");
        }
        SkillGroup group = skillGroupRepo.get(groupUuid);
        if (group == null) {
            throw new IllegalArgumentException("Skill group not found: " + groupUuid);
        }
        return group;
    }

    private void syncGroupSkills(String groupUuid) {
        if (groupUuid == null || groupUuid.isBlank()) {
            return;
        }
        SkillGroup group = skillGroupRepo.get(groupUuid);
        if (group == null) {
            return;
        }

        List<Skill> embedded = sortSkills(list().stream()
                .filter(skill -> groupUuid.equals(skill.groupUuid()))
                .toList());
        List<Skill> existing = sortSkills(group.skills() == null ? List.of() : group.skills());

        if (existing.equals(embedded)) {
            return;
        }

        SkillGroup updated = new SkillGroup(
                group.uuid(),
                group.name(),
                group.author(),
                group.category(),
                embedded,
                group.version() + 1,
                group.createdAt(),
                System.currentTimeMillis());
        skillGroupRepo.save(updated);
    }

    private static List<Skill> sortSkills(List<Skill> skills) {
        if (skills == null || skills.isEmpty()) {
            return List.of();
        }
        return skills.stream()
                .filter(skill -> skill != null && skill.uuid() != null)
                .sorted(Comparator.comparingLong(Skill::createdAt).thenComparing(Skill::uuid))
                .toList();
    }

    private List<String> findMissingDependencies(List<String> subSkillUuids, Set<String> allowedMissing) {
        if (subSkillUuids == null || subSkillUuids.isEmpty()) {
            return List.of();
        }

        List<String> missing = new ArrayList<>();
        for (String subUuid : subSkillUuids) {
            if (subUuid == null || subUuid.isBlank()) {
                continue;
            }
            if (allowedMissing.contains(subUuid)) {
                continue;
            }
            if (skillRepo.get(subUuid) == null) {
                missing.add(subUuid);
            }
        }
        return missing;
    }

    private Skill normalizeImportedSkill(Skill skill, String groupUuid) {
        long now = System.currentTimeMillis();
        return new Skill(
                skill.uuid(),
                skill.name(),
                skill.description(),
                groupUuid,
                skill.autoShareWithinGroup(),
                skill.parameters() != null ? List.copyOf(skill.parameters()) : List.of(),
                skill.instructions(),
                skill.allowedTools() != null ? List.copyOf(skill.allowedTools()) : List.of(),
                skill.allowedTypes() != null ? List.copyOf(skill.allowedTypes()) : List.of(),
                skill.subSkillUuids() != null ? List.copyOf(skill.subSkillUuids()) : List.of(),
                skill.version() < 1 ? 1 : skill.version(),
                skill.createdAt() > 0 ? skill.createdAt() : now,
                skill.updatedAt() > 0 ? skill.updatedAt() : now,
                skill.secrets() != null ? List.copyOf(skill.secrets()) : List.of());
    }

    private static String substituteNonSecretParams(String template,
                                                    List<SkillParameter> paramDefs,
                                                    Map<String, String> params) {
        String result = template;
        for (SkillParameter p : paramDefs) {
            if (!p.isSecret()) {
                String value = params.getOrDefault(p.name(), "");
                result = result.replace("{{" + p.name() + "}}", value);
            }
        }
        return result;
    }

    private static String buildInitialPrompt(Skill skill, Map<String, String> params) {
        StringBuilder sb = new StringBuilder();

        if (!skill.parameters().isEmpty()) {
            sb.append("Input Parameters:\n");
            for (SkillParameter p : skill.parameters()) {
                String val = params.getOrDefault(p.name(), "");
                sb.append("  ").append(p.name()).append(" (").append(p.type()).append("): ");
                sb.append(p.isSecret() ? "[REDACTED]" : val);
                if (!p.description().isBlank()) {
                    sb.append(" -- ").append(p.description());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        String result = sb.toString();
        return result.isBlank() ? "Begin." : result;
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(jsonString(items.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    // -- DTOs ----------------------------------------------------------------

    public record SkillExportType(String fqn, String source) {}

    public record SkillGroupExportPackage(
            String vorkSkillGroupExport,
            SkillGroup group,
            List<SkillExportType> types) {}

    public record SkillGroupImportResult(
            String status,
            String groupUuid,
            List<String> importedSkillUuids,
            List<String> missingDependencies,
            String message) {}

    public record GroupDeleteResult(boolean ok, String message) {}

    public record SkillGroupRequest(
            String name,
            String author,
            String category) {}

    public record SkillRequest(
            String name,
            String description,
            String groupUuid,
            boolean autoShareWithinGroup,
            List<SkillParameter> parameters,
            String instructions,
            List<String> allowedTools,
            List<String> allowedTypes,
            List<String> subSkillUuids,
            List<SkillSecret> secrets) {}
}
