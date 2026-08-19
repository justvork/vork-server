package sh.vork.mcp.client;

public record McpInvocationResult(
        int statusCode,
        String body
) {

    public McpInvocationResult {
        if (body == null) {
            body = "";
        }
    }
}
