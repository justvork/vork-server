package sh.vork.reflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiSession;
import sh.vork.orm.DatabaseRepository;

/**
 * Converts a reflection record into a callable AI tool callback.
 */
@Component
public class ReflectionToolCallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(ReflectionToolCallbackFactory.class);

    @Lazy
    @Autowired
    private ReflectionService reflectionService;

    @Lazy
    @Autowired
    private DatabaseRepository<AiSession> aiSessionRepository;

    private final ObjectMapper objectMapper;

    public ReflectionToolCallbackFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ToolCallback create(Reflection reflection) {
        String description = reflection.description() == null || reflection.description().isBlank()
                ? reflection.name()
                : reflection.description();
        String inputSchema = buildInputSchema(reflection.inputParameters());

        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(reflection.id())
                .description(description)
                .inputSchema(inputSchema)
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return call(toolInput, null);
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                Map<String, Object> params = parseParams(toolInput);
                List<String> missing = findMissingRequired(reflection.inputParameters(), params);
                if (!missing.isEmpty()) {
                    return jsonMissing(missing);
                }

                String bindingName = null;
                Object bindingValue = params.get("bindingName");
                if (bindingValue != null) {
                    bindingName = String.valueOf(bindingValue);
                }

                String username = resolveUsername();
                log.debug("Reflection tool invoked [id={}, username={}]", reflection.id(), username);
                return reflectionService.executeRestReflection(reflection.id(), params, bindingName, username);
            }
        };
    }

    private Map<String, Object> parseParams(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(toolInput, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            log.warn("Failed to parse reflection tool input JSON: {}", ex.getMessage());
            return Map.of();
        }
    }

    private static List<String> findMissingRequired(List<ReflectionInputParameter> parameters,
                                                    Map<String, Object> inputs) {
        if (parameters == null || parameters.isEmpty()) {
            return List.of();
        }
        return parameters.stream()
                .filter(ReflectionInputParameter::required)
                .map(ReflectionInputParameter::name)
                .filter(name -> {
                    Object value = inputs.get(name);
                    return value == null || String.valueOf(value).isBlank();
                })
                .toList();
    }

    private String jsonMissing(List<String> missing) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "missing_parameters");
            payload.put("missing", missing);
            payload.put("message", "Required parameters missing: " + String.join(", ", missing));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{\"status\":\"error\",\"message\":\"Required parameters missing\"}";
        }
    }

    private String buildInputSchema(List<ReflectionInputParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return "{\"type\":\"object\",\"properties\":{}}";
        }

        StringBuilder properties = new StringBuilder();
        boolean first = true;
        for (ReflectionInputParameter parameter : parameters) {
            if (parameter == null || parameter.name() == null || parameter.name().isBlank()) {
                continue;
            }
            if (!first) {
                properties.append(',');
            }
            properties.append('"').append(parameter.name()).append('"').append(":{")
                    .append("\"type\":\"").append(mapType(parameter.type())).append("\"");
            if (parameter.description() != null && !parameter.description().isBlank()) {
                String escaped = parameter.description()
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"");
                properties.append(",\"description\":\"").append(escaped).append("\"");
            }
            properties.append('}');
            first = false;
        }

        String required = parameters.stream()
                .filter(ReflectionInputParameter::required)
                .map(ReflectionInputParameter::name)
                .filter(name -> name != null && !name.isBlank())
                .map(name -> "\"" + name + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");

        if (properties.length() > 0) {
            properties.append(',');
        }
        properties.append("\"bindingName\":{\"type\":\"string\",\"description\":\"Optional binding name. Uses default binding when omitted.\"}");

        return "{\"type\":\"object\",\"properties\":{" + properties + "},\"required\":[" + required + "]}";
    }

    private static String mapType(String type) {
        if (type == null) {
            return "string";
        }
        return switch (type.toLowerCase()) {
            case "int", "integer" -> "integer";
            case "double", "float", "number" -> "number";
            case "boolean", "bool" -> "boolean";
            default -> "string";
        };
    }

    private String resolveUsername() {
        String sessionUuid = ToolExecutionContext.getSessionUuid();
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return null;
        }
        AiSession session = aiSessionRepository.get(sessionUuid);
        return session == null ? null : session.username();
    }
}
