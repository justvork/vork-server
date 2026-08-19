package sh.vork.ai.controller;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import sh.vork.orm.DatabaseRepository;
import sh.vork.ai.AiProvider;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.agent.ArtifactStatus;
import sh.vork.ai.service.AgentAssignmentService;
import sh.vork.ai.lifecycle.AgentTemplateSeeder;
import sh.vork.binding.BindingCatalogService;
import sh.vork.mcp.model.McpBindingStatus;
import sh.vork.mcp.service.McpBindingService;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionService;
import sh.vork.scheduling.domain.ScheduledJob;
import sh.vork.skill.Skill;
import sh.vork.skill.SkillVisibility;

/**
 * Page and REST API controller for the Agents management UI.
 *
 * <p>System agents (where {@link AgentTemplate#systemAgent()} is {@code true}) can
 * be viewed and edited but never deleted.
 */
@Controller
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);
    private static final String SNAPSHOT_VERSION = "SNAPSHOT";
    private static final int GROUP_ID_MIN_LEN = 3;
    private static final int GROUP_ID_MAX_LEN = 64;
    private static final int ARTIFACT_ID_MIN_LEN = 3;
    private static final int ARTIFACT_ID_MAX_LEN = 64;
    private static final int VERSION_MAX_LEN = 16;
    private static final Pattern GROUP_ID_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");
    private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");
    private static final ObjectMapper EXPORT_OBJECT_MAPPER = new ObjectMapper();

    private final DatabaseRepository<AgentTemplate> agentRepository;
    private final DatabaseRepository<ScheduledJob> jobRepository;
    private final DatabaseRepository<Skill> skillRepository;
    private final ReflectionService reflectionService;
    private final BindingCatalogService bindingCatalogService;
    private final AgentAssignmentService agentAssignmentService;

    @Lazy
    @Autowired
    private McpBindingService mcpBindingService;

    public AgentController(DatabaseRepository<AgentTemplate> agentRepository,
                           DatabaseRepository<ScheduledJob> jobRepository,
                           DatabaseRepository<Skill> skillRepository,
                           ReflectionService reflectionService,
                           BindingCatalogService bindingCatalogService,
                           AgentAssignmentService agentAssignmentService) {
        this.agentRepository = agentRepository;
        this.jobRepository = jobRepository;
        this.skillRepository = skillRepository;
        this.reflectionService = reflectionService;
        this.bindingCatalogService = bindingCatalogService;
        this.agentAssignmentService = agentAssignmentService;
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @GetMapping("/agents")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public String agentsPage(Model model) {
        log.debug("ENTER agentsPage");
        List<AgentTemplate> agents;
        try (var stream = agentRepository.list(0, Integer.MAX_VALUE)) {
            agents = stream
                    .filter(agent -> !agent.systemAgent())
                    .collect(Collectors.toList());
        }
        // Build uuid→name map so the template can display skill names in pills
        Map<String, String> skillNames = new java.util.HashMap<>();
        try (var stream = skillRepository.list(0, Integer.MAX_VALUE)) {
            stream.forEach(s -> skillNames.put(s.uuid(), s.name()));
        }
        model.addAttribute("agents", agents);
        model.addAttribute("skillNames", skillNames);
        return "agents";
    }

    // ── REST: list ────────────────────────────────────────────────────────────

    @GetMapping("/api/agents")
    @ResponseBody
    public List<AgentTemplate> listAgents() {
        log.debug("ENTER listAgents");
        try (var stream = agentRepository.list(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(agent -> !agent.systemAgent())
                    .collect(Collectors.toList());
        }
    }

    // ── REST: create ──────────────────────────────────────────────────────────

    @PostMapping("/api/agents")
    @ResponseBody
    @PreAuthorize("hasAuthority('AGENTS_WRITE')")
    public ResponseEntity<?> createAgent(@RequestBody AgentRequest req) {
        log.debug("ENTER createAgent: [name={}]", req.name());
        String err = validate(req, true);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        String reflectionToolErr = validateNoReflectionToolIds(req.allowedTools());
        if (reflectionToolErr != null) return ResponseEntity.badRequest().body(Map.of("error", reflectionToolErr));
        if (isAgentNameInUse(req.name(), null)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Agent name already exists."));
        }
        String skillErr = validateAssignableSkillUuids(req.skillUuids());
        if (skillErr != null) return ResponseEntity.badRequest().body(Map.of("error", skillErr));
        String recommendedModel;
        try {
            recommendedModel = normalizeRecommendedModel(req.recommendedModel());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        List<String> bindingUuids;
        try {
            bindingUuids = normalizeAndValidateBindingUuids(req.bindingUuids());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        List<String> assignedUsernames;
        try {
            assignedUsernames = agentAssignmentService.normalizeAndValidateAssignedUsernames(req.assignedUsernames());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        List<String> assignedJobUuids;
        try {
            assignedJobUuids = normalizeAndValidateAssignedJobUuids(req.jobUuids());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        String delegationErr = validateDelegationConfiguration(req.allowedTools(), assignedJobUuids, req.systemPrompt());
        if (delegationErr != null) {
            return ResponseEntity.badRequest().body(Map.of("error", delegationErr));
        }

        String groupId = req.groupId().trim();
        String artifactId = req.artifactId().trim();
        String vid = toVid(groupId, artifactId, SNAPSHOT_VERSION);
        if (agentRepository.get(vid) != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Agent artifact already exists: " + vid));
        }

        AgentTemplate agent = new AgentTemplate(
                vid,
                req.name(),
                req.systemPrompt() != null ? req.systemPrompt() : "",
                req.allowedTools() != null ? List.copyOf(req.allowedTools()) : List.of(),
                false,
                req.skillUuids() != null ? List.copyOf(req.skillUuids()) : List.of(),
                req.agentType() != null ? req.agentType() : AgentType.INTERACTIVE,
                bindingUuids,
                assignedUsernames,
                assignedJobUuids,
                recommendedModel,
                groupId,
                artifactId,
                SNAPSHOT_VERSION,
                ArtifactStatus.SNAPSHOT);
        agentRepository.save(agent);
        log.info("Agent created [id={}, name={}]", agent.uuid(), agent.name());
        return ResponseEntity.ok(agent);
    }

    // ── REST: update ──────────────────────────────────────────────────────────

    @PutMapping("/api/agents/{id}")
    @ResponseBody
    @PreAuthorize("hasAuthority('AGENTS_WRITE')")
    public ResponseEntity<?> updateAgent(@PathVariable String id, @RequestBody AgentRequest req) {
        log.debug("ENTER updateAgent: [id={}]", id);
        AgentTemplate existing = agentRepository.get(id);
        if (existing == null) return ResponseEntity.notFound().build();

        if (existing.systemAgent()) {
            log.warn("Refused to update system agent [id={}]", id);
            return ResponseEntity.status(403).body(Map.of("error", "System agents are runtime-managed and cannot be edited."));
        }
        if (!existing.isSnapshotMutable()) {
            log.warn("Refused to update non-snapshot agent [id={}, status={}]", id, existing.artifactStatus());
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT agents can be edited."));
        }

        String err = validate(req, false);
        if (err != null) return ResponseEntity.badRequest().body(Map.of("error", err));
        String reflectionToolErr = validateNoReflectionToolIds(req.allowedTools());
        if (reflectionToolErr != null) return ResponseEntity.badRequest().body(Map.of("error", reflectionToolErr));
        if (isAgentNameInUse(req.name(), id)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Agent name already exists."));
        }
        String skillErr = validateAssignableSkillUuids(req.skillUuids());
        if (skillErr != null) return ResponseEntity.badRequest().body(Map.of("error", skillErr));
        String recommendedModel;
        try {
            recommendedModel = normalizeRecommendedModel(req.recommendedModel());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        List<String> bindingUuids;
        try {
            bindingUuids = normalizeAndValidateBindingUuids(req.bindingUuids());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        List<String> assignedUsernames;
        try {
            assignedUsernames = agentAssignmentService.normalizeAndValidateAssignedUsernames(req.assignedUsernames());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        List<String> assignedJobUuids;
        try {
            assignedJobUuids = normalizeAndValidateAssignedJobUuids(req.jobUuids());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
        String delegationErr = validateDelegationConfiguration(req.allowedTools(), assignedJobUuids, req.systemPrompt());
        if (delegationErr != null) {
            return ResponseEntity.badRequest().body(Map.of("error", delegationErr));
        }

        if (req.groupId() != null && !req.groupId().isBlank()
                && !req.groupId().trim().equals(existing.groupId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "groupId is immutable after creation."));
        }
        if (req.artifactId() != null && !req.artifactId().isBlank()
                && !req.artifactId().trim().equals(existing.artifactId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "artifactId is immutable after creation."));
        }

        if (AgentTemplateSeeder.UUID_CONCIERGE.equals(existing.uuid())) {
            assignedUsernames = List.of();
        }

        AgentTemplate updated = new AgentTemplate(
                id,
                req.name(),
                req.systemPrompt() != null ? req.systemPrompt() : "",
                req.allowedTools() != null ? List.copyOf(req.allowedTools()) : List.of(),
                existing.systemAgent(), // preserve system flag
                req.skillUuids() != null ? List.copyOf(req.skillUuids()) : existing.skillUuids(),
                req.agentType() != null ? req.agentType() : existing.agentType(),
                bindingUuids,
                assignedUsernames,
                assignedJobUuids,
                recommendedModel,
                existing.groupId(),
                existing.artifactId(),
                existing.version(),
                existing.artifactStatus());
        agentRepository.save(updated);
        log.info("Agent updated [id={}, name={}]", id, req.name());
        return ResponseEntity.ok(updated);
    }

    @PutMapping("/api/agents/update")
    @ResponseBody
    @PreAuthorize("hasAuthority('AGENTS_WRITE')")
    public ResponseEntity<?> updateAgentLegacy(@RequestParam("id") String id,
                                               @RequestBody AgentRequest req) {
        return updateAgent(id, req);
    }

    // ── REST: delete ──────────────────────────────────────────────────────────

    @DeleteMapping("/api/agents/{id}")
    @ResponseBody
    @PreAuthorize("hasAuthority('AGENTS_WRITE')")
    public ResponseEntity<?> deleteAgent(@PathVariable String id) {
        log.debug("ENTER deleteAgent: [id={}]", id);
        AgentTemplate existing = agentRepository.get(id);
        if (existing == null) return ResponseEntity.notFound().build();
        if (existing.systemAgent()) {
            log.warn("Refused to delete system agent [id={}]", id);
            return ResponseEntity.status(403)
                    .body(Map.of("error", "System agents cannot be deleted."));
        }
        if (!existing.isDeletable()) {
            log.warn("Refused to delete immutable agent [id={}, status={}]", id, existing.artifactStatus());
            return ResponseEntity.status(403)
                .body(Map.of("error", "Only SNAPSHOT, SUBMITTED, or REJECTED agents can be deleted."));
        }

        agentRepository.delete(id);
        log.info("Agent deleted [id={}]", id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/api/agents/delete")
    @ResponseBody
    @PreAuthorize("hasAuthority('AGENTS_WRITE')")
    public ResponseEntity<?> deleteAgentLegacy(@RequestParam("id") String id) {
        return deleteAgent(id);
    }

    // ── Export / Import ──────────────────────────────────────────────────────

    @GetMapping("/api/agents/{id}/export")
    @ResponseBody
    public ResponseEntity<?> exportAgent(@PathVariable String id) {
        return exportAgentById(id);
    }

    @GetMapping("/api/agents/export")
    @ResponseBody
    public ResponseEntity<?> exportAgentLegacy(@RequestParam("id") String id) {
        return exportAgentById(id);
    }

    private ResponseEntity<?> exportAgentById(String id) {
        AgentTemplate agent = agentRepository.get(id);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }
        if (agent.systemAgent()) {
            return ResponseEntity.status(403).body(Map.of("error", "System agents cannot be exported as repository artifacts."));
        }

        AgentExportPackage pkg = new AgentExportPackage("1.0", agent);
        String safeName = agent.name() == null
                ? "agent"
                : agent.name().replaceAll("[^a-zA-Z0-9._-]", "_");
        String filename = "agent-" + safeName + ".json";

        String prettyJson;
        try {
            prettyJson = EXPORT_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(pkg);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize agent export payload [id={}]: {}", id, ex.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to serialize export payload."));
        }

        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
            .body(prettyJson);
    }

    @PostMapping("/api/agents/import")
    @ResponseBody
    @PreAuthorize("hasAuthority('AGENTS_WRITE')")
    public ResponseEntity<?> importAgent(@RequestBody AgentExportPackage pkg) {

        if (pkg == null || pkg.agent() == null || pkg.vorkAgentExport() == null || pkg.vorkAgentExport().isBlank()) {
            return ResponseEntity.badRequest().body(new AgentImportResult("error", null, "Invalid agent export package."));
        }

        AgentTemplate incoming = pkg.agent();
        if (incoming.systemAgent()) {
            return ResponseEntity.badRequest().body(new AgentImportResult(
                "error", null, "System agents are runtime-managed and cannot be imported."));
        }

        String groupId = incoming.groupId() == null ? "" : incoming.groupId().trim();
        String artifactId = incoming.artifactId() == null ? "" : incoming.artifactId().trim();
        String version = incoming.version() == null || incoming.version().isBlank()
            ? SNAPSHOT_VERSION
            : incoming.version().trim();
        String idValidation = validateArtifactIdentity(groupId, artifactId, version);
        if (idValidation != null) {
            return ResponseEntity.badRequest().body(new AgentImportResult("error", null, idValidation));
        }
        if (incoming.artifactStatus() != null && incoming.artifactStatus() != ArtifactStatus.SNAPSHOT) {
            return ResponseEntity.badRequest().body(new AgentImportResult(
                "error", null, "Only SNAPSHOT agents are importable in this flow."));
        }

        String incomingUuid = toVid(groupId, artifactId, version);
        if (incoming.uuid() != null && !incoming.uuid().isBlank() && !incomingUuid.equals(incoming.uuid().trim())) {
            return ResponseEntity.badRequest().body(new AgentImportResult(
                "error", incomingUuid, "Incoming uuid does not match deterministic VID."));
        }
        AgentTemplate existing = agentRepository.get(incomingUuid);

        if (existing != null && existing.systemAgent()) {
            return ResponseEntity.badRequest().body(new AgentImportResult(
                    "error", incomingUuid, "Cannot overwrite system agents via import."));
        }

        if (isAgentNameInUse(incoming.name(), incomingUuid)) {
            return ResponseEntity.badRequest().body(new AgentImportResult(
                    "error", incomingUuid, "Agent name already exists."));
        }

        String skillErr = validateAssignableSkillUuids(incoming.skillUuids());
        if (skillErr != null) {
            return ResponseEntity.badRequest().body(new AgentImportResult("error", incomingUuid, skillErr));
        }
        String reflectionToolErr = validateNoReflectionToolIds(incoming.allowedTools());
        if (reflectionToolErr != null) {
            return ResponseEntity.badRequest().body(new AgentImportResult("error", incomingUuid, reflectionToolErr));
        }

        String recommendedModel;
        try {
            recommendedModel = normalizeRecommendedModel(incoming.recommendedModel());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new AgentImportResult("error", incomingUuid, ex.getMessage()));
        }

        List<String> bindingUuids;
        try {
            bindingUuids = normalizeAndValidateBindingUuids(incoming.bindingUuids());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new AgentImportResult("error", incomingUuid, ex.getMessage()));
        }

        List<String> assignedUsernames;
        try {
            assignedUsernames = agentAssignmentService.normalizeAndValidateAssignedUsernames(incoming.assignedUsernames());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new AgentImportResult("error", incomingUuid, ex.getMessage()));
        }
        List<String> assignedJobUuids;
        try {
            assignedJobUuids = normalizeAndValidateAssignedJobUuids(incoming.jobUuids());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new AgentImportResult("error", incomingUuid, ex.getMessage()));
        }
        String delegationErr = validateDelegationConfiguration(incoming.allowedTools(), assignedJobUuids, incoming.systemPrompt());
        if (delegationErr != null) {
            return ResponseEntity.badRequest().body(new AgentImportResult("error", incomingUuid, delegationErr));
        }

        AgentTemplate imported = new AgentTemplate(
                incomingUuid,
                incoming.name(),
                incoming.systemPrompt(),
                incoming.allowedTools() == null ? List.of() : List.copyOf(incoming.allowedTools()),
                false,
                incoming.skillUuids() == null ? List.of() : List.copyOf(incoming.skillUuids()),
                incoming.agentType(),
                bindingUuids,
                assignedUsernames,
                assignedJobUuids,
                recommendedModel,
                groupId,
                artifactId,
                version,
                ArtifactStatus.SNAPSHOT);

        if (!imported.isSnapshotMutable()) {
            return ResponseEntity.badRequest().body(new AgentImportResult(
                "error", incomingUuid, "Only SNAPSHOT agents are mutable/importable in this flow."));
        }
        agentRepository.save(imported);

        String status = existing == null ? "imported" : "updated";
        return ResponseEntity.ok(new AgentImportResult(status, imported.uuid(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseBody
    public ResponseEntity<?> handleMalformedImportJson(HttpMessageNotReadableException ex) {
        Throwable root = ex.getMostSpecificCause();
        String detail = root != null && root.getMessage() != null ? root.getMessage() : ex.getMessage();

        log.warn("Agent import JSON parse failure: {}", detail, ex);

        return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Invalid JSON payload for agent import.",
                "detail", detail
        ));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String validate(AgentRequest req, boolean creating) {
        if (req.name() == null || req.name().isBlank()) return "Name is required.";
        if (creating) {
            String groupId = req.groupId() == null ? "" : req.groupId().trim();
            String artifactId = req.artifactId() == null ? "" : req.artifactId().trim();
            String identityErr = validateArtifactIdentity(groupId, artifactId, SNAPSHOT_VERSION);
            if (identityErr != null) {
                return identityErr;
            }
        }
        return null;
    }

    private static String validateArtifactIdentity(String groupId, String artifactId, String version) {
        if (groupId == null || groupId.isBlank()) {
            return "groupId is required.";
        }
        if (artifactId == null || artifactId.isBlank()) {
            return "artifactId is required.";
        }
        if (groupId.length() < GROUP_ID_MIN_LEN || groupId.length() > GROUP_ID_MAX_LEN) {
            return "groupId length must be between 3 and 64 characters.";
        }
        if (artifactId.length() < ARTIFACT_ID_MIN_LEN || artifactId.length() > ARTIFACT_ID_MAX_LEN) {
            return "artifactId length must be between 3 and 64 characters.";
        }
        if (!GROUP_ID_PATTERN.matcher(groupId).matches()) {
            return "groupId must be alphanumeric only (letters and numbers), with no spaces.";
        }
        if (!ARTIFACT_ID_PATTERN.matcher(artifactId).matches()) {
            return "artifactId must be alphanumeric only (letters and numbers), with no spaces.";
        }
        if (version == null || version.isBlank()) {
            return "version is required.";
        }
        if (version.length() > VERSION_MAX_LEN) {
            return "version length must be 16 characters or fewer.";
        }
        if (!SNAPSHOT_VERSION.equals(version)) {
            return "Only version SNAPSHOT is supported in this flow.";
        }
        return null;
    }

    private static String toVid(String groupId, String artifactId, String version) {
        return groupId + "-" + artifactId + "-" + version;
    }

    private static String normalizeRecommendedModel(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim();
        if (normalized.isBlank()) {
            return null;
        }
        int sep = normalized.indexOf(':');
        if (sep <= 0 || sep == normalized.length() - 1) {
            throw new IllegalArgumentException("recommendedModel must use format PROVIDER:model-id");
        }
        String providerKey = normalized.substring(0, sep).trim().toUpperCase();
        String modelId = normalized.substring(sep + 1).trim();
        if (modelId.isBlank()) {
            throw new IllegalArgumentException("recommendedModel must include a non-empty model id");
        }
        try {
            AiProvider.valueOf(providerKey);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unknown provider in recommendedModel: " + providerKey);
        }
        return providerKey + ":" + modelId;
    }

    private boolean isAgentNameInUse(String name, String excludeId) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String candidate = name.trim();
        try (var stream = agentRepository.list(0, Integer.MAX_VALUE)) {
            return stream.anyMatch(agent -> {
                if (excludeId != null && excludeId.equals(agent.uuid())) {
                    return false;
                }
                String existingName = agent.name() == null ? "" : agent.name().trim();
                return existingName.equalsIgnoreCase(candidate);
            });
        }
    }

    private String validateAssignableSkillUuids(List<String> skillUuids) {
        if (skillUuids == null || skillUuids.isEmpty()) {
            return null;
        }
        for (String skillUuid : skillUuids) {
            Skill skill = skillRepository.get(skillUuid);
            if (skill == null) {
                return "Unknown skill UUID: " + skillUuid;
            }
            if (skill.visibility() == SkillVisibility.PRIVATE) {
                return "Private skill cannot be attached to agents: " + skill.name();
            }
        }
        return null;
    }

    private String validateNoReflectionToolIds(List<String> allowedTools) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return null;
        }
        for (String toolId : allowedTools) {
            if (toolId == null || toolId.isBlank()) {
                continue;
            }
            Reflection reflection = reflectionService.getReflectionById(toolId.trim());
            if (reflection != null) {
                return "Reflections are not directly assignable tools. Assign reflection bindings instead for reflection ID: "
                        + reflection.id();
            }
        }
        return null;
    }

    private List<String> normalizeAndValidateBindingUuids(List<String> bindingUuids) {
        if (bindingUuids == null || bindingUuids.isEmpty()) {
            return List.of();
        }

        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        java.util.Set<String> knownBindingIds = bindingCatalogService.listBindings().stream()
                .map(binding -> binding.bindingId())
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        if (mcpBindingService != null) {
            mcpBindingService.list().stream()
                .filter(binding -> binding.status() == McpBindingStatus.ACTIVE)
                .map(binding -> binding.uuid())
                .filter(java.util.Objects::nonNull)
                .forEach(knownBindingIds::add);
        }

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

    private List<String> normalizeAndValidateAssignedJobUuids(List<String> jobUuids) {
        if (jobUuids == null || jobUuids.isEmpty()) {
            return List.of();
        }

        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        java.util.LinkedHashMap<String, String> seenByGroupArtifact = new java.util.LinkedHashMap<>();

        for (String jobUuid : jobUuids) {
            if (jobUuid == null || jobUuid.isBlank()) {
                continue;
            }
            String trimmed = jobUuid.trim();
            ScheduledJob job = jobRepository.get(trimmed);
            if (job == null) {
                throw new IllegalArgumentException("Unknown job UUID in jobUuids: " + trimmed);
            }
            String groupId = job.groupId() == null ? "" : job.groupId().trim();
            String artifactId = job.artifactId() == null ? "" : job.artifactId().trim();
            if (!groupId.isBlank() && !artifactId.isBlank()) {
                String key = groupId + ":" + artifactId;
                String existing = seenByGroupArtifact.putIfAbsent(key, trimmed);
                if (existing != null && !existing.equals(trimmed)) {
                    throw new IllegalArgumentException("Only one job version can be assigned per group/artifact pair (" + key + ").");
                }
            }
            normalized.add(trimmed);
        }
        return List.copyOf(normalized);
    }

    private static String validateDelegationConfiguration(List<String> allowedTools,
                                                          List<String> jobUuids,
                                                          String systemPrompt) {
        boolean hasDelegateTaskTool = false;
        if (allowedTools != null) {
            hasDelegateTaskTool = allowedTools.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::trim)
                    .anyMatch(tool -> "delegateTask".equalsIgnoreCase(tool));
        }
        boolean hasAssignedJobs = jobUuids != null && !jobUuids.isEmpty();

        if (hasAssignedJobs && !hasDelegateTaskTool) {
            return "Agents with assigned jobs must include delegateTask in allowedTools.";
        }
        if (hasDelegateTaskTool && !hasAssignedJobs) {
            return "Agents that include delegateTask must have at least one assigned job.";
        }
        if (hasAssignedJobs) {
            String prompt = systemPrompt == null ? "" : systemPrompt;
            if (!prompt.toLowerCase().contains("delegatetask".toLowerCase())) {
                return "Agent system prompt must describe delegateTask usage when jobs are assigned.";
            }
        }
        return null;
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    record AgentRequest(
            String       name,
            String       systemPrompt,
            List<String> allowedTools,
            List<String> skillUuids,
            AgentType    agentType,
            List<String> bindingUuids,
            List<String> assignedUsernames,
            List<String> jobUuids,
            String       recommendedModel,
            String       groupId,
            String       artifactId
    ) {}

        record AgentExportPackage(
            String vorkAgentExport,
            AgentTemplate agent
        ) {}

        record AgentImportResult(
            String status,
            String agentUuid,
            String message
        ) {}
}
