package sh.vork.mcp.client.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class McpDiscoverResultTest {

    @SuppressWarnings("unchecked")
    @Test
    void toCanonicalMapSortsEntriesDeterministically() {
        McpDiscoverResult result = new McpDiscoverResult(
                List.of(
                        new McpDiscoveredTool(
                                "z-tool",
                                "zTool",
                                "z",
                                "{}",
                                List.of(
                                        new McpDiscoveredToolParameter("z", "string", false, "", ""),
                                        new McpDiscoveredToolParameter("a", "string", true, "", ""))),
                        new McpDiscoveredTool(
                                "a-tool",
                                "aTool",
                                "a",
                                "{}",
                                List.of())),
                List.of(
                        new McpDiscoveredResource("z-resource", "Z", "", "", "{}"),
                        new McpDiscoveredResource("a-resource", "A", "", "", "{}")),
                List.of(
                        new McpDiscoveredPrompt("z-prompt", "Z", "", "{}"),
                        new McpDiscoveredPrompt("a-prompt", "A", "", "{}")));

        Map<String, Object> canonical = result.toCanonicalMap();

        List<Map<String, Object>> tools = (List<Map<String, Object>>) canonical.get("tools");
        assertEquals("a-tool", tools.get(0).get("toolId"));
        assertEquals("z-tool", tools.get(1).get("toolId"));

        List<Map<String, Object>> zToolParams = (List<Map<String, Object>>) tools.get(1).get("parameters");
        assertEquals("a", zToolParams.get(0).get("name"));
        assertEquals("z", zToolParams.get(1).get("name"));

        List<Map<String, Object>> resources = (List<Map<String, Object>>) canonical.get("resources");
        assertEquals("a-resource", resources.get(0).get("resourceId"));

        List<Map<String, Object>> prompts = (List<Map<String, Object>>) canonical.get("prompts");
        assertEquals("a-prompt", prompts.get(0).get("promptId"));
    }

    @Test
    void constructorsApplyNullSafeDefaults() {
        McpDiscoverResult result = new McpDiscoverResult(null, null, null);
        assertNotNull(result.tools());
        assertNotNull(result.resources());
        assertNotNull(result.prompts());

        McpDiscoveredTool tool = new McpDiscoveredTool(null, null, null, null, null);
        assertEquals("", tool.toolId());
        assertEquals("", tool.name());
        assertEquals("", tool.description());
        assertEquals("", tool.inputSchemaJson());
        assertEquals(List.of(), tool.parameters());
    }
}
