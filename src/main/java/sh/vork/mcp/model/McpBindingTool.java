package sh.vork.mcp.model;

import java.util.List;

import sh.vork.orm.DatabaseEntity;

/**
 * Persisted MCP tool snapshot and local behavior settings for one binding.
 */
public record McpBindingTool(
        String uuid,
        String bindingUuid,
        String toolId,
        String toolName,
        String description,
        boolean enabled,
        boolean requiresAuthorization,
        List<McpToolParameterConfig> parameterConfigs,
        String schemaHash
) implements DatabaseEntity {

    public McpBindingTool {
        if (bindingUuid == null) {
            bindingUuid = "";
        }
        if (toolId == null) {
            toolId = "";
        }
        if (toolName == null) {
            toolName = "";
        }
        if (description == null) {
            description = "";
        }
        if (parameterConfigs == null) {
            parameterConfigs = List.of();
        }
        if (schemaHash == null) {
            schemaHash = "";
        }
    }
}
