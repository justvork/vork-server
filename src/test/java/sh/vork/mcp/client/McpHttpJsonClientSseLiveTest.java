package sh.vork.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sh.vork.mcp.model.McpTransportMode;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live SSE transport tests - starts its own server-everything instance.
 *
 * <p>DISABLED: The MCP Java SDK 2.0.0 marks SSE transport as @Deprecated and recommends
 * using Streamable HTTP instead. The SSE transport exhibits session termination issues
 * with server-everything's SSE mode. Use {@link McpHttpJsonClientStreamableLiveTest}
 * for live transport testing.
 *
 * @see <a href="https://github.com/modelcontextprotocol/java-sdk">MCP Java SDK</a>
 */
@Disabled("SSE transport is deprecated in MCP SDK 2.0.0 - use Streamable HTTP instead")
class McpHttpJsonClientSseLiveTest {

    private static final Logger log = LoggerFactory.getLogger(McpHttpJsonClientSseLiveTest.class);
    // Use different port from streamable test to allow parallel execution
    private static final String BASE_URL = "http://localhost:3002";
    private static final int PORT = 3002;
    private static Process serverProcess;

    @BeforeAll
    static void startServer() throws Exception {
        log.info("Starting MCP server-everything in SSE mode on port {}", PORT);
        ProcessBuilder pb = new ProcessBuilder(
                "npx", "@modelcontextprotocol/server-everything", "sse"
        );
        pb.environment().put("PORT", String.valueOf(PORT));
        pb.inheritIO();
        serverProcess = pb.start();

        // Wait for server to be ready
        waitForServer(PORT, "/sse", 15);
        log.info("MCP server-everything started successfully");
    }

    @AfterAll
    static void stopServer() {
        if (serverProcess != null && serverProcess.isAlive()) {
            log.info("Stopping MCP server-everything");
            serverProcess.destroy();
            try {
                serverProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                serverProcess.destroyForcibly();
            }
        }
    }

    private static void waitForServer(int port, String path, int timeoutSeconds) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        URI uri = URI.create("http://localhost:" + port + path);
        while (System.currentTimeMillis() < deadline) {
            try {
                HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                conn.connect();
                int code = conn.getResponseCode();
                conn.disconnect();
                // Any response (even 4xx) means server is up
                if (code > 0) {
                    return;
                }
            } catch (IOException ignored) {
                // Server not ready yet
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Server did not start within " + timeoutSeconds + " seconds");
    }

    @Test
    @Timeout(30)
    void discoverAgainstServerEverythingSse() {
        McpHttpJsonClient client = new McpHttpJsonClient(new ObjectMapper());
        // Path defaults to /sse for SSE transport when not specified
        McpClientConfig config = new McpClientConfig(BASE_URL, McpTransportMode.SSE, "");

        var result = client.discover(config);

        assertFalse(result.tools().isEmpty(), "Expected tools from server-everything over SSE");
        boolean hasEcho = result.tools().stream().anyMatch(t -> "echo".equalsIgnoreCase(t.name()));
        assertTrue(hasEcho, "Expected server-everything to expose echo tool");
    }

    @Test
    @Timeout(30)
    void invokeEchoAgainstServerEverythingSse() {
        McpHttpJsonClient client = new McpHttpJsonClient(new ObjectMapper());
        McpClientConfig config = new McpClientConfig(BASE_URL, McpTransportMode.SSE, "");

        McpInvocationResult response = client.invokeTool(config, "echo", Map.of("message", "hello from vork"));

        assertEquals(200, response.statusCode());
        assertFalse(response.body().isBlank(), "Expected non-empty tool result body");
        assertTrue(response.body().toLowerCase().contains("hello from vork"),
                "Expected echo result to include the sent message");
    }
}
