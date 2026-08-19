package sh.vork.mcp.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import sh.vork.mcp.model.McpTransportMode;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class McpHttpJsonClientTest {

    /**
     * Verifies that when all candidate endpoints return 404, the error message
     * includes the list of tried URLs for debugging.
     */
    @Test
    void discoverErrorMessageIncludesTriedUrlsFor404() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            server.createContext("/", exchange -> writeResponse(exchange, 404, "not found"));
            server.createContext("/mcp", exchange -> writeResponse(exchange, 404, "not found"));
            server.createContext("/mcp/", exchange -> writeResponse(exchange, 404, "not found"));
            server.createContext("/rpc", exchange -> writeResponse(exchange, 404, "not found"));
            server.createContext("/streamable", exchange -> writeResponse(exchange, 404, "not found"));
            server.createContext("/streamable-http", exchange -> writeResponse(exchange, 404, "not found"));
            server.start();

            McpHttpJsonClient client = new McpHttpJsonClient(new ObjectMapper());
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            McpClientConfig config = new McpClientConfig(baseUrl, McpTransportMode.STREAMABLE_HTTP, "");

            try {
                client.discover(config);
            } catch (IllegalStateException ex) {
                // SDK error message includes "Tried:" with the list of endpoints
                assertTrue(ex.getMessage().contains("Tried:"), "Expected error message to contain 'Tried:' but was: " + ex.getMessage());
                return;
            }
            throw new AssertionError("Expected IllegalStateException");
        } finally {
            server.stop(0);
        }
    }

    private static void writeResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }
}
