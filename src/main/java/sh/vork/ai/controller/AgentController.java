package sh.vork.ai.controller;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
import org.springframework.web.bind.annotation.ResponseBody;

import sh.vork.orm.DatabaseRepository;
import sh.vork.ai.AiProvider;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.ai.service.AgentAssignmentService;
import sh.vork.ai.lifecycle.AgentTemplateSeeder;
import sh.vork.binding.BindingCatalogService;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionService;
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

    private final DatabaseRepository<AgentTemplate> agentRepository;
    private final DatabaseRepository<Skill> skillRepository;
    private final ReflectionService reflectionService;
    private final BindingCatalogService bindingCatalogService;
    private final AgentAssignmentService agentAssignmentService;

    public AgentController(DatabaseRepository<AgentTemplate> agentRepository,
                           DatabaseRepository<Skill> skillRepository,
                           ReflectionService reflectionService,
                           BindingCatalogService bindingCatalogService,
                           AgentAssignmentService agentAssignmentService) {
        this.agentRepository = agentRepository;
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
            agents = stream.collect(Collectors.toList());
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
            return stream.collect(Collectors.toList());
        }
    }

    // ── REST: create ──────────────────────────────────────────────────────────

    @PostMapping("/api/agents")
    @ResponseBody
    @PreAuthorize("hasAuthority('AGENTS_WRITE')")
    public ResponseEntity<?> createAgent(@RequestBody AgentRequest req) {
        log.debug("ENTER createAgent: [name={}]", req.name());
        String err = validate(req);
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

        AgentTemplate agent = new AgentTemplate(
                UUID.randomUUID().toString(),
                req.name(),
                req.systemPrompt() != null ? req.systemPrompt() : "",
                req.allowedTools() != null ? List.copyOf(req.allowedTools()) : List.of(),
                false,
                req.skillUuids() != null ? List.copyOf(req.skillUuids()) : List.of(),
                req.agentType() != null ? req.agentType() : AgentType.INTERACTIVE,
                bindingUuids,
                assignedUsernames,
                recommendedModel);
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

        String err = validate(req);
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
        if (AgentTemplateSeeder.UUID_CONCIERGE.equals(existing.uuid())) {
            assignedUsernames = List.of();
        }

        if (existing.systemAgent()) {
            boolean instructionsChanged = !Objects.equals(
                    req.systemPrompt() != null ? req.systemPrompt() : "",
                    existing.systemPrompt());
            boolean toolsChanged = req.allowedTools() != null
                    && !req.allowedTools().equals(existing.allowedTools());
                boolean bindingUuidsChanged = req.bindingUuids() != null
                    && !bindingUuids.equals(existing.bindingUuids());
                if (instructionsChanged || toolsChanged || bindingUuidsChanged) {
                log.warn("Refused to update instructions/tools of system agent [id={}]", id);
                return ResponseEntity.status(403).body(Map.of(
                        "error", "System agent instructions and tools are managed by the seeder "
                               + "and cannot be edited here. Update the code and restart to apply changes."));
            }
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
                recommendedModel);
        agentRepository.save(updated);
        log.info("Agent updated [id={}, name={}]", id, req.name());
        return ResponseEntity.ok(updated);
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

        agentRepository.delete(id);
        log.info("Agent deleted [id={}]", id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ── Export / Import ──────────────────────────────────────────────────────

    @GetMapping("/api/agents/{id}/export")
    @ResponseBody
    public ResponseEntity<?> exportAgent(@PathVariable String id) {
        AgentTemplate agent = agentRepository.get(id);
        if (agent == null) {
            return ResponseEntity.notFound().build();
        }

        AgentExportPackage pkg = new AgentExportPackage("1.0", agent);
        String safeName = agent.name() == null
                ? "agent"
                : agent.name().replaceAll("[^a-zA-Z0-9._-]", "_");
        String filename = "agent-" + safeName + ".json";
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(pkg);
    }

    @PostMapping("/api/agents/import")
    @ResponseBody
    @PreAuthorize("hasAuthority('AGENTS_WRITE')")
    public ResponseEntity<?> importAgent(@RequestBody AgentExportPackage pkg) {

        if (pkg == null || pkg.agent() == null || pkg.vorkAgentExport() == null || pkg.vorkAgentExport().isBlank()) {
            return ResponseEntity.badRequest().body(new AgentImportResult("error", null, "Invalid agent export package."));
        }

        AgentTemplate incoming = pkg.agent();
        String incomingUuid = incoming.uuid() == null || incoming.uuid().isBlank()
                ? UUID.randomUUID().toString()
                : incoming.uuid().trim();
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
                recommendedModel);
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

    private static String validate(AgentRequest req) {
        if (req.name() == null || req.name().isBlank()) return "Name is required.";
        return null;
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

    // ── DTO ───────────────────────────────────────────────────────────────────

    record AgentRequest(
            String       name,
            String       systemPrompt,
            List<String> allowedTools,
            List<String> skillUuids,
            AgentType    agentType,
            List<String> bindingUuids,
            List<String> assignedUsernames,
            String       recommendedModel
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
