package sh.vork.mcp.client.dto;

/**
 * Discovered MCP tool parameter contract element.
 */
public record McpDiscoveredToolParameter(
        String name,
        String schemaType,
        boolean required,
        String description,
        String defaultValue
) {

    public McpDiscoveredToolParameter {
        if (name == null) {
            name = "";
        }
        if (schemaType == null || schemaType.isBlank()) {
            schemaType = "string";
        }
        if (description == null) {
            description = "";
        }
        if (defaultValue == null) {
            defaultValue = "";
        }
    }
}
