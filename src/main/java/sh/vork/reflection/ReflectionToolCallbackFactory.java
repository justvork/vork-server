package sh.vork.reflection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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
        return create(reflection, List.of());
    }

    public ToolCallback create(Reflection reflection, List<ReflectionBinding> assignedBindings) {
        List<ReflectionBinding> effectiveBindings = assignedBindings == null ? List.of() : List.copyOf(assignedBindings);
        String baseDescription = reflection.description() == null || reflection.description().isBlank()
                ? reflection.name()
                : reflection.description();
        String bindingMetadata = effectiveBindings.isEmpty()
                ? "No bindings are assigned."
                : "Assigned bindings: " + effectiveBindings.stream()
                        .map(b -> b.name() + " (" + b.uuid() + ")")
                        .collect(Collectors.joining(", "));
        String description = baseDescription
            + " " + bindingMetadata
            + " If this tool returns an error, report that exact error to the user and do not retry with different bindingName/profile names.";
        String inputSchema = buildInputSchema(reflection.inputParameters(), effectiveBindings);

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

                if (effectiveBindings.isEmpty()) {
                    return "{\"status\":\"error\",\"message\":\"This reflection has no assigned bindings and is not callable in this context.\"}";
                }

                String resolvedBindingName = resolveBindingName(bindingName, effectiveBindings);
                if (resolvedBindingName == null) {
                    String allowedNames = effectiveBindings.stream()
                            .map(ReflectionBinding::name)
                            .collect(Collectors.joining(", "));
                    return "{\"status\":\"error\",\"message\":\"Invalid or missing bindingName. Allowed bindings: "
                            + allowedNames.replace("\"", "'") + "\"}";
                }

                String username = resolveUsername();
                log.debug("ENTER reflectionToolCall [id={}, username={}, bindingName={}, params={}]",
                        reflection.id(), username, resolvedBindingName, sanitizeForLogs(params));
                String result = reflectionService.executeRestReflection(reflection.id(), params, resolvedBindingName, username);
                String status = extractStatus(result);
                log.debug("EXIT reflectionToolCall [id={}, username={}, bindingName={}, status={}]",
                        reflection.id(), username, resolvedBindingName, status);
                if ("error".equalsIgnoreCase(status)) {
                    log.warn("Reflection tool failed [id={}, bindingName={}]. AI should report the error and stop without retrying alternative profiles.",
                            reflection.id(), resolvedBindingName);
                }
                return result;
            }
        };
    }

    private String resolveBindingName(String requestedBindingName, List<ReflectionBinding> assignedBindings) {
        if (assignedBindings == null || assignedBindings.isEmpty()) {
            return null;
        }

        if (assignedBindings.size() == 1) {
            return assignedBindings.getFirst().name();
        }

        if (requestedBindingName == null || requestedBindingName.isBlank()) {
            return null;
        }

        for (ReflectionBinding binding : assignedBindings) {
            if (requestedBindingName.equalsIgnoreCase(binding.name())
                    || requestedBindingName.equalsIgnoreCase(binding.uuid())) {
                return binding.name();
            }
        }
        return null;
    }

    private static Map<String, Object> sanitizeForLogs(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            String lowered = key.toLowerCase(Locale.ROOT);
            if (lowered.contains("secret") || lowered.contains("password") || lowered.contains("token") || lowered.contains("api_key") || lowered.contains("apikey")) {
                sanitized.put(key, "[REDACTED]");
            } else {
                sanitized.put(key, entry.getValue());
            }
        }
        return Map.copyOf(sanitized);
    }

    private String extractStatus(String result) {
        if (result == null || result.isBlank()) {
            return "unknown";
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = objectMapper.readValue(result, Map.class);
            Object status = payload.get("status");
            return status == null ? "unknown" : String.valueOf(status);
        } catch (Exception ex) {
            return "unparsed";
        }
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

    private String buildInputSchema(List<ReflectionInputParameter> parameters,
                                    List<ReflectionBinding> assignedBindings) {
        StringBuilder properties = new StringBuilder();
        boolean first = true;
        List<ReflectionInputParameter> safeParameters = parameters == null ? List.of() : parameters;
        for (ReflectionInputParameter parameter : safeParameters) {
            if (parameter == null || parameter.name() == null || parameter.name().isBlank()) {
                continue;
            }
            if (!first) {
                properties.append(',');
            }
            String schemaType = mapType(parameter.type());
            properties.append('"').append(parameter.name()).append('"').append(":{");
            if (parameter.array()) {
                properties.append("\"type\":\"array\",\"items\":{\"type\":\"")
                        .append(schemaType)
                        .append("\"}");
            } else {
                properties.append("\"type\":\"").append(schemaType).append("\"");
            }
            if (parameter.description() != null && !parameter.description().isBlank()) {
                String escaped = parameter.description()
                        .replace("\\", "\\\\")
                        .replace("\"", "\\\"");
                properties.append(",\"description\":\"").append(escaped).append("\"");
            }
            properties.append('}');
            first = false;
        }

        String required = safeParameters.stream()
                .filter(ReflectionInputParameter::required)
                .map(ReflectionInputParameter::name)
                .filter(name -> name != null && !name.isBlank())
                .map(name -> "\"" + name + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");

        if (properties.length() > 0) {
            properties.append(',');
        }
        String bindingProperty = "\"bindingName\":{\"type\":\"string\",\"description\":\"Binding name.\"";
        if (assignedBindings != null && !assignedBindings.isEmpty()) {
            String enumValues = assignedBindings.stream()
                    .map(ReflectionBinding::name)
                    .map(name -> "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                    .collect(Collectors.joining(","));
            bindingProperty += ",\"enum\":[" + enumValues + "]";
        }
        bindingProperty += "}";
        properties.append(bindingProperty);

        if (assignedBindings != null && assignedBindings.size() > 1) {
            required = required.isBlank() ? "\"bindingName\"" : required + ",\"bindingName\"";
        }

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
