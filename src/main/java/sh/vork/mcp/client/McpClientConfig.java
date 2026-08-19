package sh.vork.mcp.client;

import sh.vork.mcp.model.McpTransportMode;

/**
 * Runtime client settings for one MCP binding.
 */
public record McpClientConfig(
        String baseUrl,
        McpTransportMode transportMode,
        String authorizationHeaderValue
) {

    public McpClientConfig {
        if (baseUrl == null) {
            baseUrl = "";
        }
        if (transportMode == null) {
            transportMode = McpTransportMode.STREAMABLE_HTTP;
        }
        if (authorizationHeaderValue == null) {
            authorizationHeaderValue = "";
        }
    }
}
