package sh.vork.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import sh.vork.mcp.model.McpTransportMode;

/**
 * Diagnostic test to determine if SDK or direct HTTP fallback is used.
 * Prints directly to System.out for visibility.
 */
class McpSdkDiagnosticTest {

    // Include the endpoint path in the URL - user should enter full URL
    private static final String BASE_URL = "http://localhost:3001/mcp";

    @Test
    @Timeout(30)
    void diagnoseWhichPathIsUsed() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("MCP SDK DIAGNOSTIC TEST");
        System.out.println("=".repeat(70));
        System.out.println("Target: " + BASE_URL);
        System.out.println("Transport Mode: STREAMABLE_HTTP");
        System.out.println("-".repeat(70));

        // Create a wrapper that intercepts the flow
        McpHttpJsonClient client = new McpHttpJsonClient(new ObjectMapper()) {
            @Override
            public sh.vork.mcp.client.dto.McpDiscoverResult discover(McpClientConfig config) {
                System.out.println("[DIAG] discover() called with mode=" + config.transportMode());
                try {
                    var result = super.discover(config);
                    System.out.println("[DIAG] discover() returned " + result.tools().size() + " tools");
                    return result;
                } catch (Exception e) {
                    System.out.println("[DIAG] discover() threw: " + e.getClass().getSimpleName() + " - " + e.getMessage());
                    throw e;
                }
            }
        };

        McpClientConfig config = new McpClientConfig(BASE_URL, McpTransportMode.STREAMABLE_HTTP, "");

        System.out.println("\nCalling discover()...\n");
        try {
            var result = client.discover(config);
            System.out.println("\n" + "-".repeat(70));
            System.out.println("SUCCESS: Found " + result.tools().size() + " tools");
            System.out.println("Sample tools: " + result.tools().stream()
                    .limit(3)
                    .map(t -> t.name())
                    .toList());
        } catch (Exception e) {
            System.out.println("\nFAILED: " + e.getMessage());
            e.printStackTrace(System.out);
        }
        System.out.println("=".repeat(70) + "\n");
    }
}
