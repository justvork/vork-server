package sh.vork.mcp.client;

import org.springframework.stereotype.Component;
import sh.vork.mcp.model.McpTransportMode;

import java.util.Map;

@Component
public class McpClientFactory {

    private final McpHttpJsonClient jsonClient;

    public McpClientFactory(McpHttpJsonClient jsonClient) {
        this.jsonClient = jsonClient;
    }

    public McpClient create(McpTransportMode mode) {
        return new McpClient() {
            @Override
            public sh.vork.mcp.client.dto.McpDiscoverResult discover(McpClientConfig config) {
                return jsonClient.discover(config);
            }

            @Override
            public McpInvocationResult invokeTool(McpClientConfig config, String toolName, Map<String, Object> arguments) {
                return jsonClient.invokeTool(config, toolName, arguments);
            }
        };
    }
}
