package sh.vork.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.orm.DatabaseRepository;
import sh.vork.ai.entity.AiSession;
import sh.vork.security.SecureCredentialStore;

/**
 * Creates a {@link ToolCallback} from a {@link Skill} record so that skills are
 * presented to the AI as first-class tools rather than being dispatched via the
 * generic {@code executeSkill} indirection.
 *
 * <p>The generated tool name is derived from the skill's human-readable name via
 * {@link Skill#toolName()}.  The input schema is built dynamically from the skill's
 * declared {@link SkillParameter} list.  When the AI invokes the tool, it delegates
 * to {@link SkillService#executeSkill}, which throws
 * {@link SkillActivatedException} — the same propagation path used by the legacy
 * {@code executeSkill} bean.
 */
@Component
public class SkillToolCallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(SkillToolCallbackFactory.class);

    @Lazy
    @Autowired
    private SkillService skillService;

    @Lazy
    @Autowired
    private DatabaseRepository<AiSession> aiSessionRepository;

    @Lazy
    @Autowired
    private SecureCredentialStore secureCredentialStore;

    private final ObjectMapper objectMapper;

    public SkillToolCallbackFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Converts {@code skill} into a {@link ToolCallback} whose tool name is
     * {@link Skill#toolName()} and whose input schema is derived from the skill's
     * declared parameters.
     */
    public ToolCallback create(Skill skill) {
        String toolName    = skill.toolName();
        String description = skill.description().isBlank() ? skill.name() : skill.description();
        String inputSchema = buildInputSchema(skill.parameters());

        ToolDefinition toolDefinition = DefaultToolDefinition.builder()
                .name(toolName)
                .description(description)
                .inputSchema(inputSchema)
                .build();

        log.debug("Created skill ToolCallback [toolName={}, skillUuid={}]", toolName, skill.uuid());

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() { return toolDefinition; }

            @Override
            public String call(String toolInput) { return call(toolInput, null); }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                log.debug("Skill tool invoked [toolName={}, skillUuid={}]", toolName, skill.uuid());

                // ── Secret gate ───────────────────────────────────────────────────────────
                // Before activating the skill, ensure all declared secrets are present for
                // the current user.  Missing secrets trigger an OOB suspension form with
                // FieldSource.SECRET password fields so the user can supply them securely
                // without the values appearing in the conversation.
                if (skill.secrets() != null && !skill.secrets().isEmpty()) {
                    String sessionUuid = ToolExecutionContext.getSessionUuid();
                    String username = null;
                    if (sessionUuid != null && !sessionUuid.isBlank()) {
                        AiSession session = aiSessionRepository.get(sessionUuid);
                        if (session != null) username = session.username();
                    }
                    if (username != null && !username.isBlank()) {
                        List<SkillSecret> missing = new ArrayList<>();
                        for (SkillSecret s : skill.secrets()) {
                            if (!secureCredentialStore.hasSecret(username, s.name())) {
                                missing.add(s);
                            }
                        }
                        if (!missing.isEmpty()) {
                            log.debug("Skill secrets missing — suspending for collection [skill={}, missing={}]",
                                    skill.uuid(), missing.stream().map(SkillSecret::name).toList());
                            List<FormField> fields = missing.stream()
                                    .map(s -> new FormField(
                                            s.name(),
                                            "password",
                                            s.description().isBlank() ? s.name() : s.description(),
                                            "Enter value for " + s.name(),
                                            true,
                                            FieldSource.SECRET,
                                            null))
                                    .toList();
                            InteractionFormSchema schema = new InteractionFormSchema(
                                    "COLLECT_SKILL_SECRETS",
                                    "Credentials Required",
                                    "The skill '" + skill.name() + "' requires the following secrets to continue:",
                                    fields,
                                    List.of(new FormAction("SAVE", "Save & Continue", "primary")));
                            throw new ToolSuspensionException(
                                    toolName, toolInput,
                                    "Credentials are required for skill: " + skill.name(),
                                    schema);
                        }
                    }
                }
                // ── End secret gate ───────────────────────────────────────────────────────

                Map<String, String> params = parseParams(toolInput, skill.parameters());
                // SkillActivatedException is a RuntimeException — Spring AI's DefaultToolCallingManager
                // does not catch it, so it propagates freely to ChatService.executeAgentLoop.
                return skillService.executeSkill(skill.uuid(), params);
            }
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, String> parseParams(String toolInput, List<SkillParameter> paramDefs) {
        if (toolInput == null || toolInput.isBlank()) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = objectMapper.readValue(toolInput, Map.class);
            Map<String, String> result = new LinkedHashMap<>();
            for (SkillParameter p : paramDefs) {
                Object v = raw.get(p.name());
                if (v == null) continue;
                result.put(p.name(), v instanceof String s ? s : objectMapper.writeValueAsString(v));
            }
            // Include any extra keys the AI passed that are not in paramDefs
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (!result.containsKey(e.getKey())) {
                    Object v = e.getValue();
                    result.put(e.getKey(), v instanceof String s ? s : String.valueOf(v));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse skill tool input [input={}]: {}", toolInput, e.getMessage());
            return Map.of();
        }
    }

    private String buildInputSchema(List<SkillParameter> params) {
        if (params == null || params.isEmpty()) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        // Secret-type parameters are never exposed to the AI — the secret gate
        // handles them independently via SkillSecret declarations on the skill.
        List<SkillParameter> visible = params.stream()
                .filter(p -> !p.isSecret())
                .toList();
        if (visible.isEmpty()) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }
        StringBuilder props = new StringBuilder();
        boolean first = true;
        for (SkillParameter p : visible) {
            if (!first) props.append(",");
            props.append("\"").append(p.name()).append("\":{");
            props.append("\"type\":\"").append(mapType(p.type())).append("\"");
            if (!p.description().isBlank()) {
                String escaped = p.description().replace("\\", "\\\\").replace("\"", "\\\"");
                props.append(",\"description\":\"").append(escaped).append("\"");
            }
            props.append("}");
            first = false;
        }
        String required = visible.stream()
                .map(p -> "\"" + p.name() + "\"")
                .collect(Collectors.joining(","));
        return "{\"type\":\"object\",\"properties\":{" + props + "},\"required\":[" + required + "]}";
    }

    private static String mapType(String type) {
        if (type == null) return "string";
        return switch (type.toLowerCase()) {
            case "int"             -> "integer";
            case "double", "float" -> "number";
            case "boolean"         -> "boolean";
            default                -> "string"; // string, secret, or unknown
        };
    }
}
