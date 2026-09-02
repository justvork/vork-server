package sh.vork.skill;

import sh.vork.artifact.ArtifactStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import sh.vork.binding.BindingCatalogService;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.binding.contract.BindingContract;
import sh.vork.binding.contract.BindingContractService;
import sh.vork.mcp.model.McpBindingStatus;
import sh.vork.mcp.service.McpBindingService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.reflection.ReflectionService;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.TypeGeneratorService;

/**
 * CRUD and execution service for {@link Skill} entities and {@link SkillGroup}
 * containers.
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);
    private static final java.util.regex.Pattern IDENTITY_PATTERN = java.util.regex.Pattern.compile("^[A-Za-z0-9]{3,64}$");
    private static final String LEGACY_CONTRACT_TYPE_PREFIX = "binding_contract:";
    private static final String SESSION_REFLECTION_BINDING_UUIDS_ENV = "SESSION_REFLECTION_BINDING_UUIDS";

    private final DatabaseRepository<Skill> skillRepo;
    private final DatabaseRepository<SkillGroup> skillGroupRepo;
    private final DatabaseRepository<AiSession> aiSessionRepo;

    private static final Set<String> PARAMETER_TYPES = Set.of(
            "string",
            "text",
            "int",
            "double",
            "boolean",
            "date",
            "timestamp");

    @Lazy
    @Autowired
    private DatabaseRepository<AgentTemplate> agentTemplateRepo;

    @Lazy
    @Autowired
    private TypeGeneratorService typeGeneratorService;

    @Lazy
    @Autowired
    private DatabaseRepository<JavaType> javaTypeRepository;

    @Lazy
    @Autowired
    private BindingCatalogService bindingCatalogService;

    @Lazy
    @Autowired
    private McpBindingService mcpBindingService;

    @Lazy
    @Autowired(required = false)
    private BindingContractService bindingContractService;

    @Lazy
    @Autowired(required = false)
    private ReflectionService reflectionService;

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
            return stream
                    .sorted(Comparator.comparing(
                            group -> group.name() == null ? "" : group.name(),
                            String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public SkillGroup getGroup(String uuid) {
        log.debug("ENTER getGroup: [uuid={}]", uuid);
        return skillGroupRepo.get(uuid);
    }

    public SkillGroup createGroup(SkillGroupRequest req) {
        log.debug("ENTER createGroup: [name={}]", req.name());
        String groupId = requireIdentity(req.groupId(), "groupId");
        String artifactId = requireIdentity(req.artifactId(), "artifactId");
        String version = "SNAPSHOT";
        String uuid = toVid(groupId, artifactId, version);
        if (skillGroupRepo.get(uuid) != null) {
            throw new IllegalArgumentException("Skill group already exists: " + uuid);
        }
        long now = System.currentTimeMillis();
        SkillGroup group = new SkillGroup(
            uuid,
                req.name(),
                req.author(),
                req.category(),
                List.of(),
            groupId,
            artifactId,
            version,
            ArtifactStatus.SNAPSHOT,
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
            existing.groupId(),
            existing.artifactId(),
            existing.version(),
            existing.artifactStatus(),
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

    public List<Skill> listPublic() {
        log.debug("ENTER listPublic");
        return list().stream()
                .filter(skill -> skill.visibility() == SkillVisibility.PUBLIC)
                .toList();
    }

    public List<Skill> listVisible(boolean includePrivate) {
        return includePrivate ? list() : listPublic();
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

        List<SkillParameter> normalizedParameters = normalizeParameters(req.parameters());
        List<String> normalizedBindingUuids =
            normalizeAndValidateBindingUuids(req.bindingUuids());

        long now = System.currentTimeMillis();
        Skill skill = new Skill(
                UUID.randomUUID().toString(),
                req.name(),
                req.description(),
                req.groupUuid(),
                req.visibility() != null ? req.visibility() : SkillVisibility.PUBLIC,
                normalizedParameters,
                req.instructions(),
                req.allowedTools() != null ? List.copyOf(req.allowedTools()) : List.of(),
                req.allowedTypes() != null ? List.copyOf(req.allowedTypes()) : List.of(),
                req.subSkillUuids() != null ? List.copyOf(req.subSkillUuids()) : List.of(),
                req.recommendedModel(),
                req.outputContentType(),
                req.outputSchema(),
                1L,
                now,
                now,
                req.secrets() != null ? List.copyOf(req.secrets()) : List.of(),
                normalizedBindingUuids);
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

        List<SkillParameter> normalizedParameters = normalizeParameters(req.parameters());
        List<String> normalizedBindingUuids =
            normalizeAndValidateBindingUuids(req.bindingUuids());

        Skill updated = new Skill(
                uuid,
                req.name(),
                req.description(),
                req.groupUuid(),
                req.visibility() != null ? req.visibility() : SkillVisibility.PUBLIC,
            normalizedParameters,
                req.instructions(),
                req.allowedTools() != null ? List.copyOf(req.allowedTools()) : List.of(),
                req.allowedTypes() != null ? List.copyOf(req.allowedTypes()) : List.of(),
                req.subSkillUuids() != null ? List.copyOf(req.subSkillUuids()) : List.of(),
                req.recommendedModel(),
                req.outputContentType(),
                req.outputSchema(),
                existing.version() + 1,
                existing.createdAt(),
                System.currentTimeMillis(),
                req.secrets() != null ? List.copyOf(req.secrets()) : List.of(),
                normalizedBindingUuids);
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

        if (log.isDebugEnabled()) {
            Map<String, String> sanitized = sanitizeParamsForLog(skill, params);
            log.debug("executeSkill input parameters [skillUuid={}, skillName={}, params={}]",
                skillUuid, skill.name(), sanitized);
        }

        List<String> missing = skill.parameters().stream()
                .filter(p -> p.inputMode() == SkillParameterInputMode.AI_REQUIRED)
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

        List<String> invalid = validateTypedParameterValues(skill.parameters(), params);
        if (!invalid.isEmpty()) {
            log.info("Skill invocation invalid parameters [skill={}, invalid={}]", skillUuid, invalid);
            return "{\"status\":\"invalid_parameters\"," +
                "\"invalid\":" + toJsonArray(invalid) + "," +
                "\"message\":\"Invalid typed parameter values: " + String.join("; ", invalid)
                + ".\"}";
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
                boolean calledFromSkill = callerSession.skillStack() != null && !callerSession.skillStack().isEmpty();
                boolean reachableFromAttached = false;
                if (!calledFromSkill) {
                    List<String> roots = new ArrayList<>();
                    if (template.skillUuids() != null) {
                        roots.addAll(template.skillUuids());
                    }
                    if (callerSession.sessionSkillUuids() != null) {
                        roots.addAll(callerSession.sessionSkillUuids());
                    }
                    reachableFromAttached = isReachableFromAttachedRoots(skillUuid, roots);
                }

                if (skill.visibility() == SkillVisibility.PRIVATE) {
                    if (!calledFromSkill && !reachableFromAttached) {
                        log.warn("Private skill access denied — caller is not inside a skill and skill is not reachable from attached roots [session={}, skill={}]",
                                callerSessionUuid, skillUuid);
                        return "{\"status\":\"error\",\"message\":\"Skill '" + skill.name()
                                + "' is private and can only be called from another skill in the same group.\"}";
                    }

                    if (calledFromSkill) {
                        SkillFrame callerFrame = callerSession.skillStack().getLast();
                        Skill callerSkill = callerFrame == null ? null : skillRepo.get(callerFrame.skillUuid());
                        boolean sameGroup = callerSkill != null
                                && callerSkill.groupUuid() != null
                                && callerSkill.groupUuid().equals(skill.groupUuid());
                        if (!sameGroup) {
                            log.warn("Private skill access denied — cross-group invocation [session={}, skill={}, callerSkill={}]",
                                    callerSessionUuid, skillUuid, callerSkill == null ? "null" : callerSkill.uuid());
                            return "{\"status\":\"error\",\"message\":\"Skill '" + skill.name()
                                    + "' is private and can only be called by skills from its own group.\"}";
                        }
                    }
                }

                boolean inAgentSkills = template.skillUuids().contains(skillUuid);
                boolean inSessionSkills = callerSession.sessionSkillUuids() != null
                        && callerSession.sessionSkillUuids().contains(skillUuid);
                if (!calledFromSkill && !inAgentSkills && !inSessionSkills && !reachableFromAttached) {
                    log.warn("Skill access denied — skill not assigned to agent or session [session={}, agent={}, skill={}]",
                            callerSessionUuid, agentId, skillUuid);
                    return "{\"status\":\"error\",\"message\":\"Skill '" + skill.name()
                            + "' is not assigned to this agent. Only skills configured for your agent may be executed.\"}";
                }
            }
        }

            ContractBindingResolution contractBindingResolution = resolveContractBindingInputs(skill, params, callerSession);
            if (!contractBindingResolution.invalidReasons().isEmpty()) {
                String details = String.join("; ", contractBindingResolution.invalidReasons());
                log.info("Skill invocation invalid binding-contract parameters [skill={}, invalid={}]", skillUuid,
                    contractBindingResolution.invalidReasons());
                return "{\"status\":\"invalid_parameters\"," +
                    "\"invalid\":" + toJsonArray(contractBindingResolution.invalidReasons()) + "," +
                    "\"message\":\"Invalid binding-contract parameters: " + details
                    + ".\"}";
            }

        String resolvedInstructions = substituteNonSecretParams(skill.instructions(), skill.parameters(), params);
        SkillFrame frame = new SkillFrame(
                skillUuid, skill.name(), resolvedInstructions,
                skill.allowedTools(), skill.allowedTypes(), params,
                contractBindingResolution.runtimeBindingUuids(),
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

    private boolean isReachableFromAttachedRoots(String targetSkillUuid, List<String> rootSkillUuids) {
        if (targetSkillUuid == null || targetSkillUuid.isBlank() || rootSkillUuids == null || rootSkillUuids.isEmpty()) {
            return false;
        }
        LinkedHashSet<String> roots = new LinkedHashSet<>(rootSkillUuids);
        for (String rootUuid : roots) {
            if (targetSkillUuid.equals(rootUuid)) {
                return true;
            }
            Skill root = skillRepo.get(rootUuid);
            if (root == null) {
                continue;
            }
            List<Skill> effective = resolveEffectiveSubSkills(root);
            boolean found = effective.stream().anyMatch(s -> s != null && targetSkillUuid.equals(s.uuid()));
            if (found) {
                return true;
            }
        }
        return false;
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
                ids.add(peer.uuid());
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
            group.groupId(),
            group.artifactId(),
                group.version(),
            group.artifactStatus(),
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
        String normalizedGroupId = nonBlank(incomingGroup.groupId(), "legacy");
        String normalizedArtifactId = nonBlank(incomingGroup.artifactId(), "skillgroup");
        String normalizedVersion = nonBlank(incomingGroup.version(), "SNAPSHOT");
        String normalizedUuid = toVid(normalizedGroupId, normalizedArtifactId, normalizedVersion);
        List<Skill> normalizedSkills = incomingSkills.stream()
            .map(skill -> normalizeImportedSkill(skill, normalizedUuid))
            .toList();
        SkillGroup normalizedGroup = new SkillGroup(
            normalizedUuid,
                incomingGroup.name(),
                incomingGroup.author(),
                incomingGroup.category(),
            normalizedSkills,
            normalizedGroupId,
            normalizedArtifactId,
            normalizedVersion,
            incomingGroup.artifactStatus() == null ? ArtifactStatus.SNAPSHOT : incomingGroup.artifactStatus(),
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
                group.groupId(),
                group.artifactId(),
                group.version(),
                group.artifactStatus(),
                group.createdAt(),
                System.currentTimeMillis());
        skillGroupRepo.save(updated);
    }

    private static String requireIdentity(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required.");
        }
        String trimmed = value.trim();
        if (!IDENTITY_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(field + " must be alphanumeric and 3-64 characters.");
        }
        return trimmed;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String toVid(String groupId, String artifactId, String version) {
        return groupId + "-" + artifactId + "-" + version;
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
                skill.visibility(),
                skill.parameters() != null ? List.copyOf(skill.parameters()) : List.of(),
                skill.instructions(),
                skill.allowedTools() != null ? List.copyOf(skill.allowedTools()) : List.of(),
                skill.allowedTypes() != null ? List.copyOf(skill.allowedTypes()) : List.of(),
                skill.subSkillUuids() != null ? List.copyOf(skill.subSkillUuids()) : List.of(),
                skill.recommendedModel(),
                skill.outputContentType(),
                skill.outputSchema(),
                skill.version() < 1 ? 1 : skill.version(),
                skill.createdAt() > 0 ? skill.createdAt() : now,
                skill.updatedAt() > 0 ? skill.updatedAt() : now,
                skill.secrets() != null ? List.copyOf(skill.secrets()) : List.of(),
                skill.bindingUuids() != null ? List.copyOf(skill.bindingUuids()) : List.of());
    }

    private List<String> normalizeAndValidateBindingUuids(List<String> bindingUuids) {
        if (bindingUuids == null || bindingUuids.isEmpty()) {
            return List.of();
        }

        Set<String> knownBindingIds = bindingCatalogService.listBindings().stream()
                .map(binding -> binding.bindingId())
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (mcpBindingService != null) {
            mcpBindingService.list().stream()
                .filter(binding -> binding.status() == McpBindingStatus.ACTIVE)
                .map(binding -> binding.uuid())
                .filter(java.util.Objects::nonNull)
                .forEach(knownBindingIds::add);
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String bindingUuid : bindingUuids) {
            if (bindingUuid == null || bindingUuid.isBlank()) {
                continue;
            }
            String trimmed = bindingUuid.trim();
            if (!knownBindingIds.contains(trimmed)) {
                throw new IllegalArgumentException("Unknown binding UUID in bindingUuids: " + trimmed);
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
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

    private List<SkillParameter> normalizeParameters(List<SkillParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }

        List<SkillParameter> normalized = new ArrayList<>();
        for (SkillParameter parameter : parameters) {
            if (parameter == null) {
                continue;
            }

            String type = normalizeParameterType(parameter.type());
            if ("secret".equals(type)) {
                throw new IllegalArgumentException("Parameter type 'secret' is not supported. Use the Secrets section instead.");
            }
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("Unsupported parameter type: " + parameter.type());
            }

            normalized.add(new SkillParameter(
                    parameter.name(),
                    type,
                    parameter.description(),
                    parameter.inputMode()));
        }

        return List.copyOf(normalized);
    }

    private String normalizeParameterType(String rawType) {
        String candidate = rawType == null || rawType.isBlank() ? "string" : rawType.trim();
        String normalized = candidate.toLowerCase(Locale.ROOT);
        if (PARAMETER_TYPES.contains(normalized) || "secret".equals(normalized)) {
            return normalized;
        }

        String contractVid = resolveBindingContractType(candidate);
        if (contractVid != null) {
            return contractVid;
        }

        if (normalized.startsWith(LEGACY_CONTRACT_TYPE_PREFIX)) {
            String trimmedContract = candidate.substring(candidate.indexOf(':') + 1).trim();
            if (!trimmedContract.isBlank()) {
                String resolved = resolveBindingContractType(trimmedContract);
                return resolved != null ? resolved : null;
            }
        }

        return null;
    }

    private String resolveBindingContractType(String contractIdCandidate) {
        if (contractIdCandidate == null || contractIdCandidate.isBlank() || bindingContractService == null) {
            return null;
        }
        BindingContract contract = bindingContractService.getContract(contractIdCandidate.trim());
        if (contract == null) {
            String lowered = contractIdCandidate.trim().toLowerCase(Locale.ROOT);
            if (!lowered.equals(contractIdCandidate.trim())) {
                contract = bindingContractService.getContract(lowered);
            }
        }
        return contract == null ? null : contract.uuid();
    }

    private ContractBindingResolution resolveContractBindingInputs(Skill skill,
                                                                   Map<String, String> params,
                                                                   AiSession callerSession) {
        if (skill == null || skill.parameters() == null || skill.parameters().isEmpty()) {
            return new ContractBindingResolution(List.of(), List.of());
        }

        List<String> invalidReasons = new ArrayList<>();
        LinkedHashSet<String> runtimeBindingUuids = new LinkedHashSet<>();
        Set<String> accessibleBindingUuids = collectAccessibleBindingUuids(callerSession);

        for (SkillParameter parameter : skill.parameters()) {
            if (parameter == null) {
                continue;
            }

            String expectedContractVid = resolveBindingContractType(parameter.type());
            if (expectedContractVid == null) {
                continue;
            }

            String rawValue = params.get(parameter.name());
            if (rawValue == null || rawValue.isBlank()) {
                continue;
            }

            String bindingUuid = rawValue.trim();
            if (!accessibleBindingUuids.contains(bindingUuid)) {
                invalidReasons.add(parameter.name() + " must reference an attached binding VID");
                continue;
            }

            if (reflectionService == null) {
                invalidReasons.add(parameter.name() + " cannot be validated because reflection services are unavailable");
                continue;
            }

            ReflectionBinding binding = reflectionService.getBindingByUuid(bindingUuid);
            if (binding == null) {
                invalidReasons.add(parameter.name() + " must reference a reflection binding implementing contract '"
                        + expectedContractVid + "'");
                continue;
            }

            ReflectionGroup bindingGroup = reflectionService.getBindingGroup(binding);
            if (bindingGroup == null || bindingGroup.bindingContractUuids() == null) {
                invalidReasons.add(parameter.name() + " binding has no associated reflection group contracts");
                continue;
            }

            boolean implementsContract = bindingGroup.bindingContractUuids().stream()
                    .anyMatch(contractVid -> contractVid != null && contractVid.equalsIgnoreCase(expectedContractVid));
            if (!implementsContract) {
                invalidReasons.add(parameter.name() + " binding does not implement contract '" + expectedContractVid + "'");
                continue;
            }

            runtimeBindingUuids.add(bindingUuid);
        }

        return new ContractBindingResolution(List.copyOf(runtimeBindingUuids), List.copyOf(invalidReasons));
    }

    private Set<String> collectAccessibleBindingUuids(AiSession callerSession) {
        LinkedHashSet<String> resolved = new LinkedHashSet<>();

        if (callerSession == null) {
            return Set.of();
        }

        resolved.addAll(parseDelimitedUuids(
                callerSession.environmentVariables() == null ? null
                        : callerSession.environmentVariables().get(SESSION_REFLECTION_BINDING_UUIDS_ENV)));

        String agentTemplateId = callerSession.getActiveAgentTemplateId();
        if (agentTemplateId != null && !agentTemplateId.isBlank() && agentTemplateRepo != null) {
            AgentTemplate template = agentTemplateRepo.get(agentTemplateId);
            if (template != null && template.bindingUuids() != null) {
                for (String bindingUuid : template.bindingUuids()) {
                    if (bindingUuid != null && !bindingUuid.isBlank()) {
                        resolved.add(bindingUuid.trim());
                    }
                }
            }
        }

        if (callerSession.skillStack() != null && !callerSession.skillStack().isEmpty()) {
            SkillFrame topFrame = callerSession.skillStack().getLast();
            if (topFrame != null && topFrame.runtimeBindingUuids() != null) {
                for (String bindingUuid : topFrame.runtimeBindingUuids()) {
                    if (bindingUuid != null && !bindingUuid.isBlank()) {
                        resolved.add(bindingUuid.trim());
                    }
                }
            }
            if (topFrame != null && topFrame.skillUuid() != null && !topFrame.skillUuid().isBlank()) {
                Skill topSkill = skillRepo.get(topFrame.skillUuid());
                if (topSkill != null && topSkill.bindingUuids() != null) {
                    for (String bindingUuid : topSkill.bindingUuids()) {
                        if (bindingUuid != null && !bindingUuid.isBlank()) {
                            resolved.add(bindingUuid.trim());
                        }
                    }
                }
            }
        }

        return Set.copyOf(resolved);
    }

    private static List<String> parseDelimitedUuids(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        for (String token : raw.split("[,;\\n\\r\\t ]+")) {
            if (token == null) {
                continue;
            }
            String trimmed = token.trim();
            if (!trimmed.isBlank()) {
                parsed.add(trimmed);
            }
        }
        return List.copyOf(parsed);
    }

    private static String buildInitialPrompt(Skill skill, Map<String, String> params) {
        StringBuilder sb = new StringBuilder();

        List<String> requiredNames = skill.parameters().stream()
                .filter(p -> p != null && !p.isSecret())
                .filter(p -> p.inputMode() == SkillParameterInputMode.AI_REQUIRED)
                .map(SkillParameter::name)
                .toList();

        List<String> missingRequired = requiredNames.stream()
                .filter(name -> {
                    String value = params.get(name);
                    return value == null || value.isBlank();
                })
                .toList();

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

            sb.append("Parameter Usage Contract:\n");
            sb.append("- You MUST use the exact parameter values listed above when calling downstream tools.\n");
            sb.append("- AI-required parameters are inferred runtime inputs: derive missing values from the current user request and conversation context.\n");
            sb.append("- Do NOT use stale values from unrelated prior turns when the current request provides constraints.\n");
            sb.append("- Do NOT omit required parameters when the downstream tool supports them.\n");
            if (!requiredNames.isEmpty()) {
                sb.append("- Required parameters for this skill: ")
                        .append(String.join(", ", requiredNames))
                        .append("\n");
            }
            if (!missingRequired.isEmpty()) {
                sb.append("- Missing required parameters detected: ")
                        .append(String.join(", ", missingRequired))
                        .append("\n");
                sb.append("- Infer these missing required values from the current request before proceeding; only return an explicit error if genuinely ambiguous.\n");
            }
            sb.append("\n");
        }

        String result = sb.toString();
        return result.isBlank() ? "Begin." : result;
    }

    private static List<String> validateTypedParameterValues(List<SkillParameter> parameterDefs,
                                                             Map<String, String> params) {
        if (parameterDefs == null || parameterDefs.isEmpty() || params == null || params.isEmpty()) {
            return List.of();
        }

        List<String> invalid = new ArrayList<>();
        for (SkillParameter parameter : parameterDefs) {
            if (parameter == null || parameter.isSecret()) {
                continue;
            }
            String rawValue = params.get(parameter.name());
            if (rawValue == null || rawValue.isBlank()) {
                continue;
            }

            String type = parameter.type() == null ? "string" : parameter.type().trim().toLowerCase(Locale.ROOT);
            if ("date".equals(type) && !isIsoDate(rawValue.trim())) {
                invalid.add(parameter.name() + " expects YYYY-MM-DD");
            } else if ("timestamp".equals(type) && !isIsoOffsetTimestamp(rawValue.trim())) {
                invalid.add(parameter.name() + " expects ISO 8601 date-time with timezone/offset");
            }
        }
        return List.copyOf(invalid);
    }

    private static boolean isIsoDate(String value) {
        try {
            LocalDate parsed = LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            return DateTimeFormatter.ISO_LOCAL_DATE.format(parsed).equals(value);
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private static boolean isIsoOffsetTimestamp(String value) {
        try {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
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

    private static Map<String, String> sanitizeParamsForLog(Skill skill, Map<String, String> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }

        Map<String, Boolean> secretByName = new LinkedHashMap<>();
        if (skill != null && skill.parameters() != null) {
            for (SkillParameter p : skill.parameters()) {
                if (p != null && p.name() != null && !p.name().isBlank()) {
                    secretByName.put(p.name(), p.isSecret());
                }
            }
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            boolean declaredSecret = Boolean.TRUE.equals(secretByName.get(key));
            boolean heuristicSecret = isSensitiveParamName(key);
            if (declaredSecret || heuristicSecret) {
                out.put(key, "[REDACTED]");
                continue;
            }
            out.put(key, abbreviateForLog(value));
        }
        return out;
    }

    private static boolean isSensitiveParamName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase();
        return n.contains("secret")
                || n.contains("password")
                || n.contains("token")
                || n.contains("apikey")
                || n.contains("api_key")
                || n.contains("credential");
    }

    private static String abbreviateForLog(String value) {
        if (value == null) {
            return "<null>";
        }
        if (value.length() <= 300) {
            return value;
        }
        return value.substring(0, 300) + "...<truncated>";
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
            String category,
            String groupId,
            String artifactId) {}

    public record SkillRequest(
            String name,
            String description,
            String groupUuid,
            SkillVisibility visibility,
            List<SkillParameter> parameters,
            String instructions,
            List<String> allowedTools,
            List<String> allowedTypes,
            List<String> subSkillUuids,
            String recommendedModel,
            String outputContentType,
            String outputSchema,
            List<SkillSecret> secrets,
            List<String> bindingUuids) {}

    private record ContractBindingResolution(
            List<String> runtimeBindingUuids,
            List<String> invalidReasons) {}
}
