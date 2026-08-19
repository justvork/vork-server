package sh.vork.mcp.service;

import org.junit.jupiter.api.Test;
import sh.vork.mcp.client.dto.McpDiscoverResult;
import sh.vork.mcp.client.dto.McpDiscoveredPrompt;
import sh.vork.mcp.client.dto.McpDiscoveredResource;
import sh.vork.mcp.client.dto.McpDiscoveredTool;
import sh.vork.mcp.client.dto.McpDiscoveredToolParameter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpContractDiffServiceTest {

    private final McpContractDiffService service = new McpContractDiffService();

    @Test
    void doesNotDriftWhenPreviousToolSchemaIsStoredAsHashAndCurrentIsRawJson() {
        String schema = "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}";
        String schemaHash = sha256(schema);

        McpDiscoverResult previous = new McpDiscoverResult(
                List.of(new McpDiscoveredTool("weather.lookup", "weather.lookup", "", schemaHash, List.of())),
                List.of(),
                List.of());

        McpDiscoverResult current = new McpDiscoverResult(
                List.of(new McpDiscoveredTool("weather.lookup", "weather.lookup", "", schema, List.of())),
                List.of(),
                List.of());

        McpContractDiffService.McpContractDiff diff = service.diff(previous, current);

        assertFalse(diff.drifted(), "Expected no drift for equivalent tool schema hash/raw forms");
    }

    @Test
    void driftsWhenToolSchemaChanges() {
        String oldSchema = "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}";
        String newSchema = "{\"type\":\"object\",\"properties\":{\"zip\":{\"type\":\"string\"}}}";

        McpDiscoverResult previous = new McpDiscoverResult(
                List.of(new McpDiscoveredTool("weather.lookup", "weather.lookup", "", sha256(oldSchema), List.of())),
                List.of(new McpDiscoveredResource("r1", "r1", "", "uri", "{}")),
                List.of(new McpDiscoveredPrompt("p1", "p1", "", "{}")));

        McpDiscoverResult current = new McpDiscoverResult(
                List.of(new McpDiscoveredTool("weather.lookup", "weather.lookup", "", newSchema,
                        List.of(new McpDiscoveredToolParameter("zip", "string", true, "", "")))),
                List.of(new McpDiscoveredResource("r1", "r1", "", "uri", "{}")),
                List.of(new McpDiscoveredPrompt("p1", "p1", "", "{}")));

        McpContractDiffService.McpContractDiff diff = service.diff(previous, current);

        assertTrue(diff.drifted(), "Expected drift when tool schema changes");
        assertTrue(diff.tools().hasChanges(), "Tool section should report changes");
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
