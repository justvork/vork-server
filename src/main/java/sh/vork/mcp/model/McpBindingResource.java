package sh.vork.mcp.model;

import sh.vork.orm.DatabaseEntity;

/**
 * Persisted metadata snapshot of one discovered MCP resource.
 */
public record McpBindingResource(
        String uuid,
        String bindingUuid,
        String resourceId,
        String name,
        String description,
        String uriTemplate,
        String schemaJson,
        String schemaHash
) implements DatabaseEntity {

    public McpBindingResource {
        if (bindingUuid == null) {
            bindingUuid = "";
        }
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
        if (schemaHash == null) {
            schemaHash = "";
        }
    }
}
