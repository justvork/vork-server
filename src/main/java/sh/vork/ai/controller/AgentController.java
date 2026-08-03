package sh.vork.ai.controller;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import sh.vork.orm.DatabaseRepository;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.ai.agent.AgentType;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionBindingAssignment;
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

    public AgentController(DatabaseRepository<AgentTemplate> agentRepository,
                           DatabaseRepository<Skill> skillRepository,
                           ReflectionService reflectionService) {
        this.agentRepository = agentRepository;
        this.skillRepository = skillRepository;
        this.reflectionService = reflectionService;
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @GetMapping("/agents")
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
        List<ReflectionBindingAssignment> reflectionBindings;
        try {
            reflectionBindings = normalizeAndValidateReflectionBindings(req.reflectionBindings());
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
                reflectionBindings);
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
        List<ReflectionBindingAssignment> reflectionBindings;
        try {
            reflectionBindings = normalizeAndValidateReflectionBindings(req.reflectionBindings());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }

        if (existing.systemAgent()) {
            boolean instructionsChanged = !Objects.equals(
                    req.systemPrompt() != null ? req.systemPrompt() : "",
                    existing.systemPrompt());
            boolean toolsChanged = req.allowedTools() != null
                    && !req.allowedTools().equals(existing.allowedTools());
            boolean reflectionBindingsChanged = req.reflectionBindings() != null
                    && !reflectionBindings.equals(existing.reflectionBindings());
            if (instructionsChanged || toolsChanged || reflectionBindingsChanged) {
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
                reflectionBindings);
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String validate(AgentRequest req) {
        if (req.name() == null || req.name().isBlank()) return "Name is required.";
        return null;
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

    private List<ReflectionBindingAssignment> normalizeAndValidateReflectionBindings(
            List<ReflectionBindingAssignment> reflectionBindings) {
        if (reflectionBindings == null || reflectionBindings.isEmpty()) {
            return List.of();
        }

        java.util.LinkedHashMap<String, java.util.LinkedHashSet<String>> merged = new java.util.LinkedHashMap<>();
        for (ReflectionBindingAssignment assignment : reflectionBindings) {
            if (assignment == null || assignment.reflectionId() == null || assignment.reflectionId().isBlank()) {
                continue;
            }
            String reflectionId = assignment.reflectionId().trim();
            Reflection reflection = reflectionService.getReflectionById(reflectionId);
            if (reflection == null) {
                throw new IllegalArgumentException("Unknown reflection ID in reflectionBindings: " + reflectionId);
            }

            java.util.LinkedHashSet<String> bucket = merged.computeIfAbsent(reflectionId,
                    ignored -> new java.util.LinkedHashSet<>());
            for (String bindingUuid : assignment.bindingUuids()) {
                ReflectionBinding binding = reflectionService.getBindingByUuid(bindingUuid);
                if (binding == null) {
                    throw new IllegalArgumentException("Unknown reflection binding UUID in reflectionBindings: " + bindingUuid);
                }
                if (!reflection.groupUuid().equals(binding.groupUuid())) {
                    throw new IllegalArgumentException(
                            "Binding UUID " + bindingUuid + " does not belong to reflection '" + reflectionId + "' group.");
                }
                bucket.add(binding.uuid());
            }
        }

        return merged.entrySet().stream()
                .map(entry -> new ReflectionBindingAssignment(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }

    // ── DTO ───────────────────────────────────────────────────────────────────

    record AgentRequest(
            String       name,
            String       systemPrompt,
            List<String> allowedTools,
            List<String> skillUuids,
            AgentType    agentType,
            List<ReflectionBindingAssignment> reflectionBindings
    ) {}
}
