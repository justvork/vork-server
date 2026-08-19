package sh.vork.mcp.model;

import sh.vork.orm.DatabaseEntity;

/**
 * Persisted metadata snapshot of one discovered MCP prompt.
 */
public record McpBindingPrompt(
        String uuid,
        String bindingUuid,
        String promptId,
        String name,
        String description,
        String argumentSchemaJson,
        String schemaHash
) implements DatabaseEntity {

    public McpBindingPrompt {
        if (bindingUuid == null) {
            bindingUuid = "";
        }
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
        if (schemaHash == null) {
            schemaHash = "";
        }
    }
}
