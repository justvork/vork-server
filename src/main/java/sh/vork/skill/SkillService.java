package sh.vork.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.agent.AgentTemplate;
import sh.vork.orm.DatabaseRepository;
import sh.vork.ai.session.SessionToolStore;
import sh.vork.typegen.JavaType;
import sh.vork.typegen.TypeGeneratorService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * CRUD and execution service for {@link Skill} entities.
 *
 * <p>Skill execution pushes a {@link SkillFrame} onto the calling session's
 * {@code skillStack} and throws {@link SkillActivatedException} to break out
 * of Spring AI's tool chain.  {@link sh.vork.ai.service.ChatService} catches
 * the exception and runs an inline sub-loop with the skill's restricted tool
 * set and custom system prompt until the AI returns {@code FINISHED_TURN},
 * at which point the frame is popped and textResponse is the skill output.
 */
@Service
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    private final DatabaseRepository<Skill>     skillRepo;
    private final DatabaseRepository<AiSession> aiSessionRepo;
    private final SessionToolStore              sessionToolStore;

    @Lazy
    @Autowired
    private DatabaseRepository<AgentTemplate> agentTemplateRepo;

    /** TypeGeneratorService — used during skill import to compile embedded types. */
    @Lazy
    @Autowired
    private TypeGeneratorService typeGeneratorService;

    /** JavaType repository — used during skill export to embed record sources. */
    @Lazy
    @Autowired
    private DatabaseRepository<JavaType> javaTypeRepository;

    public SkillService(DatabaseRepository<Skill> skillRepo,
                        DatabaseRepository<AiSession> aiSessionRepo,
                        SessionToolStore sessionToolStore) {
        this.skillRepo       = skillRepo;
        this.aiSessionRepo   = aiSessionRepo;
        this.sessionToolStore = sessionToolStore;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public List<Skill> list() {
        log.debug("ENTER list");
        try (var stream = skillRepo.list(0, Integer.MAX_VALUE)) {
            return stream.collect(Collectors.toList());
        }
    }

    public Skill get(String uuid) {
        log.debug("ENTER get: [uuid={}]", uuid);
        return skillRepo.get(uuid);
    }

    public Skill create(SkillRequest req) {
        log.debug("ENTER create: [name={}]", req.name());
        long now = System.currentTimeMillis();
        Skill skill = new Skill(
                UUID.randomUUID().toString(),
                req.name(),
                req.author(),
                req.description(),
                req.category(),
                req.parameters() != null ? List.copyOf(req.parameters()) : List.of(),
                req.outputTemplate(),
                req.instructions(),
                req.allowedTools() != null ? List.copyOf(req.allowedTools()) : List.of(),
                req.allowedTypes() != null ? List.copyOf(req.allowedTypes()) : List.of(),
                req.subSkillUuids() != null ? List.copyOf(req.subSkillUuids()) : List.of(),
                1L,
                now,
                now,
                req.secrets() != null ? List.copyOf(req.secrets()) : List.of());
        skillRepo.save(skill);
        log.info("Skill created [uuid={}, name={}]", skill.uuid(), skill.name());
        return skill;
    }

    public Skill update(String uuid, SkillRequest req) {
        log.debug("ENTER update: [uuid={}]", uuid);
        Skill existing = skillRepo.get(uuid);
        if (existing == null) return null;
        Skill updated = new Skill(
                uuid,
                req.name(),
                req.author(),
                req.description(),
                req.category(),
                req.parameters() != null ? List.copyOf(req.parameters()) : List.of(),
                req.outputTemplate(),
                req.instructions(),
                req.allowedTools() != null ? List.copyOf(req.allowedTools()) : List.of(),
                req.allowedTypes() != null ? List.copyOf(req.allowedTypes()) : List.of(),
                req.subSkillUuids() != null ? List.copyOf(req.subSkillUuids()) : List.of(),
                existing.version() + 1,
                existing.createdAt(),
                System.currentTimeMillis(),
                req.secrets() != null ? List.copyOf(req.secrets()) : List.of());
        skillRepo.save(updated);
        log.info("Skill updated [uuid={}, name={}, version={}]", uuid, updated.name(), updated.version());
        return updated;
    }

    public void delete(String uuid) {
        log.debug("ENTER delete: [uuid={}]", uuid);
        skillRepo.delete(uuid);
        log.info("Skill deleted [uuid={}]", uuid);
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    /**
     * Executes a skill in a sandboxed child {@link AiSession}.
     *
     * <p>Before launching, validates that all declared {@link SkillParameter}s
     * are present in {@code parameters}.  If any are missing the tool returns
     * a {@code missing_parameters} status so the calling agent can prompt the
     * user to provide them.
     *
     * <p>Secret parameter values are stored and forwarded but masked in all
     * log output.
     *
     * @param skillUuid  UUID of the skill to run
     * @param parameters map of parameter name→value supplied by the calling agent
     * @return JSON result: {@code {status,output}} on success,
     *         {@code {status,missing,message}} when params are absent, or
     *         {@code {status,sessionUuid,message}} when suspended/failed
     */
    public String executeSkill(String skillUuid, Map<String, String> parameters) {
        log.debug("ENTER executeSkill: [skillUuid={}, paramKeys={}]", skillUuid,
                parameters == null ? "null" : parameters.keySet());

        Skill skill = skillRepo.get(skillUuid);
        if (skill == null) {
            return "{\"status\":\"error\",\"message\":\"Skill not found: " + skillUuid + "\"}";
        }

        Map<String, String> params = parameters != null ? parameters : Map.of();

        // Validate all declared parameters are present
        List<String> missing = skill.parameters().stream()
                .filter(p -> {
                    String val = params.get(p.name());
                    return val == null || val.isBlank();
                })
                .map(SkillParameter::name)
                .toList();

        if (!missing.isEmpty()) {
            log.info("Skill invocation missing parameters [skill={}, missing={}]", skillUuid, missing);
            return "{\"status\":\"missing_parameters\","
                    + "\"missing\":" + toJsonArray(missing) + ","
                    + "\"message\":\"Required parameters missing: " + String.join(", ", missing)
                    + ". Please collect these values from the user and retry.\"}";
        }

        // Get the caller session and push a skill frame onto its stack
        String callerSessionUuid = ToolExecutionContext.getSessionUuid();
        if (callerSessionUuid == null || callerSessionUuid.isBlank()) {
            return "{\"status\":\"error\",\"message\":\"executeSkill must be called from within an active session\"}";
        }
        AiSession callerSession = aiSessionRepo.get(callerSessionUuid);
        if (callerSession == null) {
            return "{\"status\":\"error\",\"message\":\"Caller session not found: " + callerSessionUuid + "\"}";
        }

        // Authorization: when an agent template is active and has skills assigned,
        // only skills in that agent's skillUuids list OR the session's sessionSkillUuids list may be executed.
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

        // Build and push the skill context frame.
        // Substitute non-secret {{paramName}} tokens so the model sees resolved values in the system prompt.
        String resolvedInstructions = substituteNonSecretParams(skill.instructions(), skill.parameters(), params);
        // startMessageCount is set to messages.size() + 1 (not size()) because the current round's
        // USER message has not been persisted yet at this point — it is appended to the session only
        // inside sendMessage's ToolSuspensionException handler.  The +1 skip ensures that on an
        // authorization-resume the skill AI receives only the TOOL result(s) from the authorized
        // call, with no background-job seed message or task-prompt leaking into the skill context.
        SkillFrame frame = new SkillFrame(
                skillUuid, skill.name(), resolvedInstructions, skill.outputTemplate(),
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

        // Build initial prompt from typed parameters
        String initialPrompt = buildInitialPrompt(skill, params);

        log.info("Skill activated [session={}, skill={}, stackDepth={}]",
                callerSessionUuid, skillUuid, newStack.size());

        // Throw to break out of Spring AI's tool chain; caught by ChatService.executeAgentLoop
        throw new SkillActivatedException(skillUuid, skill.name(), initialPrompt);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Replaces {@code {{paramName}}} tokens in {@code template} with the corresponding
     * parameter values, skipping parameters marked as secret to avoid leaking credentials
     * into the AI system prompt.
     */
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
                // Mask secret values in the prompt to avoid leaking them in AI context
                sb.append(p.isSecret() ? "[REDACTED]" : val);
                if (!p.description().isBlank()) {
                    sb.append(" — ").append(p.description());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Skill output constraints are expected to be authored directly inside skill.instructions().
        // This user turn carries only resolved input parameters.

        String result = sb.toString();
        // If there are no parameters at all, send a minimal trigger so the model has a
        // non-empty first user turn to respond to.
        return result.isBlank() ? "Begin." : result;
    }

    @SuppressWarnings("unused")
    private static String resolveUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) return auth.getName();
        } catch (Exception ignored) {}
        return "system";
    }

    private static String jsonString(String value) {
        if (value == null) return "null";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String toJsonArray(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(jsonString(items.get(i)));
        }
        sb.append("]");
        return sb.toString();
    }

    // ── Export / Import ───────────────────────────────────────────────────────

    /**
     * Builds an export package for the given skill, embedding the Java source
     * of every type referenced in {@code allowedTypes}.
     *
     * @return the package, or {@code null} if the skill is not found
     */
    public SkillExportPackage export(String uuid) {
        log.debug("ENTER export: [uuid={}]", uuid);
        Skill skill = skillRepo.get(uuid);
        if (skill == null) return null;

        List<SkillExportType> types = new ArrayList<>();
        if (!skill.allowedTypes().isEmpty()) {
            for (String fqn : skill.allowedTypes()) {
                JavaType jt = javaTypeRepository.get(fqn);
                if (jt != null && jt.source() != null) {
                    types.add(new SkillExportType(fqn, jt.source()));
                } else {
                    log.debug("Source not found for type {} — skipping from export", fqn);
                }
            }
        }

        log.debug("EXIT export: [uuid={}, embeddedTypes={}]", uuid, types.size());
        return new SkillExportPackage("1.0", skill, types);
    }

    /**
     * Imports a skill from an export package.
     *
     * <p>If the skill UUID already exists in the database the import is rejected
     * with status {@code already_installed}.  Otherwise each embedded Java type
     * is compiled and persisted before the skill itself is saved.
     */
    public SkillImportResult importSkill(SkillExportPackage pkg) {
        log.debug("ENTER importSkill: [skillUuid={}]",
                pkg != null && pkg.skill() != null ? pkg.skill().uuid() : "null");

        if (pkg == null || pkg.skill() == null) {
            return new SkillImportResult("error", null, "Invalid export package.");
        }

        Skill skill = pkg.skill();

        if (skillRepo.get(skill.uuid()) != null) {
            log.info("Skill already installed [uuid={}, name={}]", skill.uuid(), skill.name());
            return new SkillImportResult("already_installed", skill.uuid(),
                    "Skill '" + skill.name() + "' is already installed.");
        }

        // Compile and register embedded types first so they are available at runtime.
        List<String> typeErrors = new ArrayList<>();
        if (pkg.types() != null) {
            for (SkillExportType t : pkg.types()) {
                try {
                    typeGeneratorService.compileAndSave(t.source());
                    log.debug("Compiled imported type [fqn={}]", t.fqn());
                } catch (Exception e) {
                    log.warn("Failed to compile type {} during skill import: {}", t.fqn(), e.getMessage());
                    typeErrors.add(t.fqn() + ": " + e.getMessage());
                }
            }
        }

        // Persist the skill with its original UUID (preserves identity across instances).
        skillRepo.save(skill);

        String msg = typeErrors.isEmpty() ? null
                : "Imported with type compilation errors: " + String.join("; ", typeErrors);
        log.info("Skill imported [uuid={}, name={}, typeErrors={}]",
                skill.uuid(), skill.name(), typeErrors.size());
        return new SkillImportResult("imported", skill.uuid(), msg);
    }

    // ── Request / Export DTOs ─────────────────────────────────────────────────

    /** Embedded type entry inside a skill export package. */
    public record SkillExportType(String fqn, String source) {}

    /** Top-level export envelope written to / read from the JSON file. */
    public record SkillExportPackage(
            String vorkSkillExport,
            Skill skill,
            List<SkillExportType> types) {}

    /** Result of a skill import operation. */
    public record SkillImportResult(String status, String uuid, String message) {}

    // ── Request DTO ───────────────────────────────────────────────────────────

    public record SkillRequest(
            String                name,
            String                author,
            String                description,
            String                category,
            List<SkillParameter>  parameters,
            String                outputTemplate,
            String                instructions,
            List<String>          allowedTools,
            List<String>          allowedTypes,
            List<String>          subSkillUuids,
            List<SkillSecret>     secrets
    ) {}
}
