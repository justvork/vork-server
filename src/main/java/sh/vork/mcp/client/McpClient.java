package sh.vork.mcp.client;

import java.util.Map;

import sh.vork.mcp.client.dto.McpDiscoverResult;

public interface McpClient {

    McpDiscoverResult discover(McpClientConfig config);

    McpInvocationResult invokeTool(McpClientConfig config, String toolName, Map<String, Object> arguments);
}
