package sh.vork.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import sh.vork.mcp.client.McpClient;
import sh.vork.mcp.client.McpClientConfig;
import sh.vork.mcp.client.McpClientFactory;
import sh.vork.mcp.client.McpInvocationResult;
import sh.vork.mcp.model.McpBinding;
import sh.vork.mcp.model.McpBindingTool;
import sh.vork.mcp.model.McpToolParameterConfig;
import sh.vork.mcp.model.McpToolParameterInputMode;
import sh.vork.ai.security.AuthorizationRuleEngine;
import sh.vork.ai.security.SecuredToolCallback;
import sh.vork.security.SecureCredentialStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class McpToolCallbackFactory {

    private static final Logger log = LoggerFactory.getLogger(McpToolCallbackFactory.class);

    private final ObjectMapper objectMapper;
    private final McpClientFactory clientFactory;
    private final SecureCredentialStore secureCredentialStore;
    private final McpParameterResolutionService parameterResolutionService;
    private final AuthorizationRuleEngine authorizationRuleEngine;

    public McpToolCallbackFactory(ObjectMapper objectMapper,
                                  McpClientFactory clientFactory,
                                  SecureCredentialStore secureCredentialStore,
                                  McpParameterResolutionService parameterResolutionService,
                                  AuthorizationRuleEngine authorizationRuleEngine) {
        this.objectMapper = objectMapper;
        this.clientFactory = clientFactory;
        this.secureCredentialStore = secureCredentialStore;
        this.parameterResolutionService = parameterResolutionService;
        this.authorizationRuleEngine = authorizationRuleEngine;
    }

    public ToolCallback create(McpBinding binding, McpBindingTool bindingTool) {
        String callbackName = buildToolName(binding, bindingTool);
        String description = buildDescription(binding, bindingTool);
        String inputSchema = buildInputSchema(bindingTool.parameterConfigs());

        ToolDefinition definition = DefaultToolDefinition.builder()
                .name(callbackName)
                .description(description)
                .inputSchema(inputSchema)
                .build();

        ToolCallback callback = new ToolCallback() {
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
                Map<String, Object> provided = parseInput(toolInput);
                String username = currentUsername();
                McpParameterResolutionService.ResolvedParameters resolved = parameterResolutionService.resolve(
                        callbackName,
                        bindingTool.toolName(),
                        provided,
                        bindingTool.parameterConfigs(),
                        username);
                if (!resolved.missing().isEmpty()) {
                    return missingResponse(resolved.missing());
                }

                String authHeader = loadSecret(username, binding.authorizationSecretRef());

                McpClient client = clientFactory.create(binding.transportMode());
                McpInvocationResult result = client.invokeTool(
                        new McpClientConfig(binding.baseUrl(), binding.transportMode(), authHeader),
                        bindingTool.toolName(),
                        resolved.arguments());

                log.debug("MCP tool invoked [bindingUuid={}, bindingName={}, toolId={}, toolName={}, statusCode={}]",
                        binding.uuid(), binding.name(), bindingTool.toolId(), bindingTool.toolName(), result.statusCode());

                return result.body();
            }
        };

        if (bindingTool.requiresAuthorization() && authorizationRuleEngine != null) {
            return new SecuredToolCallback(callback, authorizationRuleEngine, true);
        }

        return callback;
    }

    private static String buildToolName(McpBinding binding, McpBindingTool bindingTool) {
        String prefix = slug(binding.name());
        String base = bindingTool.toolId() == null || bindingTool.toolId().isBlank()
                ? bindingTool.toolName()
                : bindingTool.toolId();
        return "mcp_" + prefix + "__" + slug(base);
    }

    private static String buildDescription(McpBinding binding, McpBindingTool bindingTool) {
        String baseDescription = bindingTool.description() == null || bindingTool.description().isBlank()
                ? bindingTool.toolName()
                : bindingTool.description();
        return baseDescription + " [MCP binding: " + binding.name() + ", source tool: " + bindingTool.toolName() + "]";
    }

    private String buildInputSchema(List<McpToolParameterConfig> configs) {
        List<McpToolParameterConfig> safeConfigs = configs == null ? List.of() : configs;
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (McpToolParameterConfig config : safeConfigs) {
            if (!isModelVisible(config.inputMode())) {
                continue;
            }
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("type", config.schemaType() == null || config.schemaType().isBlank() ? "string" : config.schemaType());
            if (config.description() != null && !config.description().isBlank()) {
                field.put("description", config.description());
            }
            properties.put(config.name(), field);
            if (config.inputMode() == McpToolParameterInputMode.AI_REQUIRED || config.requiredByServer()) {
                required.add(config.name());
            }
        }

        root.put("properties", properties);
        root.put("required", required);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            return "{\"type\":\"object\",\"properties\":{},\"required\":[]}";
        }
    }

    private static boolean isModelVisible(McpToolParameterInputMode inputMode) {
        return inputMode == McpToolParameterInputMode.AI_REQUIRED
                || inputMode == McpToolParameterInputMode.AI_OPTIONAL;
    }

    private Map<String, Object> parseInput(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(toolInput, Map.class);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            log.warn("Failed to parse MCP tool input JSON: {}", ex.getMessage());
            return Map.of();
        }
    }

    private static String missingResponse(List<String> missing) {
        String names = String.join(", ", missing);
        return "{\"status\":\"missing_parameters\",\"message\":\"Required parameters missing: "
                + names.replace("\"", "'") + "\",\"missing\":["
                + missing.stream().map(n -> "\"" + n.replace("\"", "'") + "\"").reduce((a, b) -> a + "," + b).orElse("")
                + "]}";
    }

    private String loadSecret(String username, String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return "";
        }
        String secret = secureCredentialStore.getSecretForUser(username, secretRef);
        return secret == null ? "" : secret;
    }

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "system";
        }
        return auth.getName();
    }

    private static String slug(String value) {
        if (value == null || value.isBlank()) {
            return "unnamed";
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String compact = sb.toString().replaceAll("_+", "_");
        if (compact.startsWith("_")) {
            compact = compact.substring(1);
        }
        if (compact.endsWith("_")) {
            compact = compact.substring(0, compact.length() - 1);
        }
        return compact.isBlank() ? "unnamed" : compact;
    }

}
