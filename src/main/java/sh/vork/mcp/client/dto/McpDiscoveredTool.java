package sh.vork.mcp.client.dto;

import java.util.List;

/**
 * Tool description discovered from an MCP endpoint.
 */
public record McpDiscoveredTool(
        String toolId,
        String name,
        String description,
        String inputSchemaJson,
        List<McpDiscoveredToolParameter> parameters
) {

    public McpDiscoveredTool {
        if (toolId == null) {
            toolId = "";
        }
        if (name == null) {
            name = "";
        }
        if (description == null) {
            description = "";
        }
        if (inputSchemaJson == null) {
            inputSchemaJson = "";
        }
        if (parameters == null) {
            parameters = List.of();
        }
    }
}
