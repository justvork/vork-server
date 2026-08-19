package sh.vork.mcp.client.dto;

/**
 * Prompt metadata discovered from an MCP endpoint.
 */
public record McpDiscoveredPrompt(
        String promptId,
        String name,
        String description,
        String argumentSchemaJson
) {

    public McpDiscoveredPrompt {
        if (promptId == null) {
            promptId = "";
        }
        if (name == null) {
            name = "";
        }
        if (description == null) {
            description = "";
        }
        if (argumentSchemaJson == null) {
            argumentSchemaJson = "";
        }
    }
}
