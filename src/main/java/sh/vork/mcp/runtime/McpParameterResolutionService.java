package sh.vork.mcp.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.mcp.model.McpToolParameterConfig;
import sh.vork.mcp.model.McpToolParameterInputMode;
import sh.vork.security.SecureCredentialStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class McpParameterResolutionService {

    private static final Logger log = LoggerFactory.getLogger(McpParameterResolutionService.class);

    static final String MCP_INPUT_TOKEN_PARAM = "__mcpInputToken";
    private static final String MCP_INPUT_TOKEN_CTX_PREFIX = "mcp.input.token.";
    private static final String MCP_RUNTIME_OVERRIDE_TOOL_PREFIX = "mcp.runtime.override.";

    private final SecureCredentialStore secureCredentialStore;

    public McpParameterResolutionService(SecureCredentialStore secureCredentialStore) {
        this.secureCredentialStore = secureCredentialStore;
    }

    public ResolvedParameters resolve(String callbackToolName,
                                      String displayToolName,
                                      Map<String, Object> provided,
                                      List<McpToolParameterConfig> configs,
                                      String username) {
        log.debug("ENTER resolveMcpParameters: callbackToolName={}, configCount={}",
                callbackToolName, configs == null ? 0 : configs.size());

        List<McpToolParameterConfig> safeConfigs = configs == null ? List.of() : configs;
        Map<String, Object> safeProvided = provided == null ? Map.of() : provided;
        Map<String, Object> runtimeOverrides = resolveRuntimeOverrides(callbackToolName);

        enforceSecretPromptIfNeeded(callbackToolName, displayToolName, safeProvided, runtimeOverrides, safeConfigs, username);
        enforceUserPromptIfNeeded(callbackToolName, displayToolName, safeProvided, runtimeOverrides, safeConfigs);

        Map<String, Object> resolved = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        for (McpToolParameterConfig config : safeConfigs) {
            String paramName = config.name();

            if (config.inputMode() == McpToolParameterInputMode.FIXED) {
                if (config.defaultValue() != null && !config.defaultValue().isBlank()) {
                    resolved.put(paramName, config.defaultValue());
                } else {
                    missing.add(paramName);
                }
                continue;
            }

            Object value = runtimeOverrides.get(paramName);
            if (isNonBlank(value)) {
                resolved.put(paramName, value);
                continue;
            }

            value = safeProvided.get(paramName);
            if (isNonBlank(value)) {
                if (config.inputMode() == McpToolParameterInputMode.SECRET) {
                    String secretRef = config.bindingSecretRef();
                    if (secretRef != null && !secretRef.isBlank()) {
                        secureCredentialStore.saveSecretForUser(username, secretRef, String.valueOf(value));
                    }
                }
                resolved.put(paramName, value);
                continue;
            }

            if (config.inputMode() == McpToolParameterInputMode.SECRET) {
                String secretValue = loadSecret(username, config.bindingSecretRef());
                if (!secretValue.isBlank()) {
                    resolved.put(paramName, secretValue);
                    continue;
                }
            }

            if (config.defaultValue() != null && !config.defaultValue().isBlank()) {
                resolved.put(paramName, config.defaultValue());
                continue;
            }

            if (isRequired(config)) {
                missing.add(paramName);
            }
        }

        log.debug("EXIT resolveMcpParameters: callbackToolName={}, resolvedCount={}, missingCount={}",
                callbackToolName, resolved.size(), missing.size());
        return new ResolvedParameters(resolved, missing);
    }

    private void enforceSecretPromptIfNeeded(String callbackToolName,
                                             String displayToolName,
                                             Map<String, Object> provided,
                                             Map<String, Object> runtimeOverrides,
                                             List<McpToolParameterConfig> configs,
                                             String username) {
        List<FormField> fields = buildSecretInputFields(configs, provided, runtimeOverrides, username);
        if (fields.isEmpty()) {
            return;
        }

        log.debug("Suspending MCP tool invocation for secret input [tool={}]", callbackToolName);
        InteractionFormSchema schema = new InteractionFormSchema(
                "COLLECT_MCP_SECRETS",
                "MCP Secrets Required",
                "Enter required secret values for this MCP tool.",
                fields,
                List.of(new FormAction("SAVE", "Save & Continue", "primary")));
        throw new ToolSuspensionException(
                callbackToolName,
                "{}",
                "Secret input is required for MCP tool: " + displayToolName,
                schema);
    }

    private void enforceUserPromptIfNeeded(String callbackToolName,
                                           String displayToolName,
                                           Map<String, Object> provided,
                                           Map<String, Object> runtimeOverrides,
                                           List<McpToolParameterConfig> configs) {
        if (!requiresUserPrompt(configs)) {
            clearPromptToken(callbackToolName);
            return;
        }

        boolean needsPrompt = shouldPromptForInput(configs, provided, runtimeOverrides);
        String tokenKey = MCP_INPUT_TOKEN_CTX_PREFIX + callbackToolName;
        String expectedToken = contextString(tokenKey);
        String providedToken = asString(provided.get(MCP_INPUT_TOKEN_PARAM));
        boolean confirmed = expectedToken != null && !expectedToken.isBlank() && expectedToken.equals(providedToken);

        if (!needsPrompt) {
            ToolExecutionContext.remove(tokenKey);
            return;
        }

        if (confirmed) {
            ToolExecutionContext.remove(tokenKey);
            return;
        }

        String resumeToken = UUID.randomUUID().toString();
        ToolExecutionContext.put(tokenKey, resumeToken);
        List<FormField> fields = buildForcedInputFields(configs, provided, runtimeOverrides, resumeToken);
        if (fields.isEmpty()) {
            return;
        }

        log.debug("Suspending MCP tool invocation for user input [tool={}]", callbackToolName);
        InteractionFormSchema schema = new InteractionFormSchema(
                "COLLECT_MCP_INPUT",
                "MCP Input Required",
                "Review and confirm MCP input values before execution.",
                fields,
                List.of(new FormAction("SAVE", "Save & Continue", "primary")));
        throw new ToolSuspensionException(
                callbackToolName,
                "{}",
                "User input is required for MCP tool: " + displayToolName,
                schema);
    }

    private static boolean requiresUserPrompt(List<McpToolParameterConfig> configs) {
        for (McpToolParameterConfig config : configs) {
            if (config.inputMode() == McpToolParameterInputMode.USER_ALWAYS_PROMPT
                    || config.inputMode() == McpToolParameterInputMode.USER_PROMPT_IF_EMPTY) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldPromptForInput(List<McpToolParameterConfig> configs,
                                                Map<String, Object> provided,
                                                Map<String, Object> runtimeOverrides) {
        for (McpToolParameterConfig config : configs) {
            if (config.inputMode() == McpToolParameterInputMode.USER_ALWAYS_PROMPT) {
                return true;
            }

            if (config.inputMode() == McpToolParameterInputMode.USER_PROMPT_IF_EMPTY) {
                Object overrideValue = runtimeOverrides.get(config.name());
                if (isNonBlank(overrideValue)) {
                    continue;
                }

                Object providedValue = provided.get(config.name());
                if (isNonBlank(providedValue)) {
                    continue;
                }

                if (config.defaultValue() != null && !config.defaultValue().isBlank()) {
                    continue;
                }

                return true;
            }
        }
        return false;
    }

    private static List<FormField> buildForcedInputFields(List<McpToolParameterConfig> configs,
                                                          Map<String, Object> provided,
                                                          Map<String, Object> runtimeOverrides,
                                                          String resumeToken) {
        List<FormField> fields = new ArrayList<>();
        for (McpToolParameterConfig config : configs) {
            if (config.inputMode() != McpToolParameterInputMode.USER_ALWAYS_PROMPT
                    && config.inputMode() != McpToolParameterInputMode.USER_PROMPT_IF_EMPTY) {
                continue;
            }

            Object overrideValue = runtimeOverrides.get(config.name());
            Object providedValue = provided.get(config.name());
            String currentValue = isNonBlank(providedValue)
                    ? String.valueOf(providedValue)
                    : (isNonBlank(overrideValue) ? String.valueOf(overrideValue) : "");

            if (config.inputMode() == McpToolParameterInputMode.USER_PROMPT_IF_EMPTY
                    && !currentValue.isBlank()) {
                continue;
            }

            fields.add(new FormField(
                    config.name(),
                    forcedInputFieldType(config.schemaType()),
                    config.name(),
                    config.description() == null || config.description().isBlank()
                            ? "Enter " + config.name()
                            : config.description(),
                    currentValue,
                    true,
                    FieldSource.CONVERSATION,
                    null));
        }

        fields.add(new FormField(
                MCP_INPUT_TOKEN_PARAM,
                "hidden",
                MCP_INPUT_TOKEN_PARAM,
                resumeToken,
                resumeToken,
                true,
                FieldSource.CONVERSATION,
                null));

        return fields;
    }

    private List<FormField> buildSecretInputFields(List<McpToolParameterConfig> configs,
                                                   Map<String, Object> provided,
                                                   Map<String, Object> runtimeOverrides,
                                                   String username) {
        List<FormField> fields = new ArrayList<>();
        for (McpToolParameterConfig config : configs) {
            if (config.inputMode() != McpToolParameterInputMode.SECRET) {
                continue;
            }

            String secretRef = config.bindingSecretRef();
            if (secretRef != null && !secretRef.isBlank()) {
                String existing = loadSecret(username, secretRef);
                if (!existing.isBlank()) {
                    continue;
                }
            }

            Object overrideValue = runtimeOverrides.get(config.name());
            if (isNonBlank(overrideValue)) {
                continue;
            }

            Object providedValue = provided.get(config.name());
            if (isNonBlank(providedValue)) {
                continue;
            }

            fields.add(new FormField(
                    config.name(),
                    "password",
                    config.name(),
                    config.description() == null || config.description().isBlank()
                            ? "Enter secret for " + config.name()
                            : config.description(),
                    "",
                    true,
                    FieldSource.SECRET,
                    null));
        }
        return fields;
    }

    private static String forcedInputFieldType(String schemaType) {
        if (schemaType == null || schemaType.isBlank()) {
            return "text";
        }
        return switch (schemaType.toLowerCase()) {
            case "integer", "number", "int", "double", "float" -> "number";
            case "boolean" -> "checkbox";
            default -> "text";
        };
    }

    private static boolean isRequired(McpToolParameterConfig config) {
        if (config.requiredByServer()) {
            return true;
        }
        return config.inputMode() == McpToolParameterInputMode.AI_REQUIRED
                || config.inputMode() == McpToolParameterInputMode.SECRET
                || config.inputMode() == McpToolParameterInputMode.USER_ALWAYS_PROMPT
                || config.inputMode() == McpToolParameterInputMode.USER_PROMPT_IF_EMPTY;
    }

    private Map<String, Object> resolveRuntimeOverrides(String callbackToolName) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        if (callbackToolName == null || callbackToolName.isBlank()) {
            return overrides;
        }

        Object global = ToolExecutionContext.get(MCP_RUNTIME_OVERRIDE_TOOL_PREFIX + callbackToolName);
        if (global instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                overrides.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }

        return overrides;
    }

    private void clearPromptToken(String callbackToolName) {
        if (callbackToolName == null || callbackToolName.isBlank()) {
            return;
        }
        ToolExecutionContext.remove(MCP_INPUT_TOKEN_CTX_PREFIX + callbackToolName);
    }

    private String loadSecret(String username, String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return "";
        }
        String secret = secureCredentialStore.getSecretForUser(username, secretRef);
        return secret == null ? "" : secret;
    }

    private static String contextString(String key) {
        Object value = ToolExecutionContext.get(key);
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private static boolean isNonBlank(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String stringValue) {
            return !stringValue.isBlank();
        }
        return true;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public record ResolvedParameters(Map<String, Object> arguments, List<String> missing) {
    }
}
