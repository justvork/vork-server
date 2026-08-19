package sh.vork.mcp.model;

/**
 * Stored parameter behavior for a discovered MCP tool parameter.
 */
public record McpToolParameterConfig(
        String name,
        String schemaType,
        boolean requiredByServer,
        String description,
        String defaultValue,
        McpToolParameterInputMode inputMode,
        String bindingSecretRef
) {

    public McpToolParameterConfig {
        if (name == null) {
            name = "";
        }
        if (schemaType == null || schemaType.isBlank()) {
            schemaType = "string";
        }
        if (description == null) {
            description = "";
        }
        if (inputMode == null) {
            inputMode = McpToolParameterInputMode.AI_OPTIONAL;
        }
        if (defaultValue == null) {
            defaultValue = "";
        }
        if (bindingSecretRef == null) {
            bindingSecretRef = "";
        }
    }
}
