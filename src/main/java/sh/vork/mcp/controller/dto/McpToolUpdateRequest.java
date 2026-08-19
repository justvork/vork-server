package sh.vork.mcp.controller.dto;

import sh.vork.mcp.model.McpToolParameterInputMode;

import java.util.List;

public record McpToolUpdateRequest(
        boolean enabled,
        boolean requiresAuthorization,
        List<ParameterConfig> parameterConfigs
) {
    public record ParameterConfig(
            String name,
            McpToolParameterInputMode inputMode,
            String defaultValue,
            String bindingSecretValue
    ) {
    }
}
