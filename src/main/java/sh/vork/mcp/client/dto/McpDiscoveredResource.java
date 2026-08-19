package sh.vork.mcp.client.dto;

/**
 * Resource metadata discovered from an MCP endpoint.
 */
public record McpDiscoveredResource(
        String resourceId,
        String name,
        String description,
        String uriTemplate,
        String schemaJson
) {

    public McpDiscoveredResource {
        if (resourceId == null) {
            resourceId = "";
        }
        if (name == null) {
            name = "";
        }
        if (description == null) {
            description = "";
        }
        if (uriTemplate == null) {
            uriTemplate = "";
        }
        if (schemaJson == null) {
            schemaJson = "";
        }
    }
}
