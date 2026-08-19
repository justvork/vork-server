package sh.vork.mcp.client.dto;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Full discovered MCP contract snapshot.
 */
public record McpDiscoverResult(
        List<McpDiscoveredTool> tools,
        List<McpDiscoveredResource> resources,
        List<McpDiscoveredPrompt> prompts
) {

    public McpDiscoverResult {
        if (tools == null) {
            tools = List.of();
        }
        if (resources == null) {
            resources = List.of();
        }
        if (prompts == null) {
            prompts = List.of();
        }
    }

    /**
     * Produces a deterministic map representation used by downstream hash services.
     */
    public Map<String, Object> toCanonicalMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tools", canonicalTools());
        root.put("resources", canonicalResources());
        root.put("prompts", canonicalPrompts());
        return root;
    }

    private List<Map<String, Object>> canonicalTools() {
        List<McpDiscoveredTool> ordered = new ArrayList<>(tools);
        ordered.sort(Comparator
                .comparing(McpDiscoveredTool::toolId, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(McpDiscoveredTool::name, String.CASE_INSENSITIVE_ORDER));

        List<Map<String, Object>> result = new ArrayList<>();
        for (McpDiscoveredTool tool : ordered) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolId", tool.toolId());
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.put("inputSchemaJson", tool.inputSchemaJson());

            List<McpDiscoveredToolParameter> params = new ArrayList<>(tool.parameters());
            params.sort(Comparator.comparing(McpDiscoveredToolParameter::name, String.CASE_INSENSITIVE_ORDER));

            List<Map<String, Object>> canonicalParams = new ArrayList<>();
            for (McpDiscoveredToolParameter p : params) {
                Map<String, Object> param = new LinkedHashMap<>();
                param.put("name", p.name());
                param.put("schemaType", p.schemaType());
                param.put("required", p.required());
                param.put("description", p.description());
                param.put("defaultValue", p.defaultValue());
                canonicalParams.add(param);
            }
            item.put("parameters", canonicalParams);

            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> canonicalResources() {
        List<McpDiscoveredResource> ordered = new ArrayList<>(resources);
        ordered.sort(Comparator
                .comparing(McpDiscoveredResource::resourceId, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(McpDiscoveredResource::name, String.CASE_INSENSITIVE_ORDER));

        List<Map<String, Object>> result = new ArrayList<>();
        for (McpDiscoveredResource resource : ordered) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("resourceId", resource.resourceId());
            item.put("name", resource.name());
            item.put("description", resource.description());
            item.put("uriTemplate", resource.uriTemplate());
            item.put("schemaJson", resource.schemaJson());
            result.add(item);
        }
        return result;
    }

    private List<Map<String, Object>> canonicalPrompts() {
        List<McpDiscoveredPrompt> ordered = new ArrayList<>(prompts);
        ordered.sort(Comparator
                .comparing(McpDiscoveredPrompt::promptId, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(McpDiscoveredPrompt::name, String.CASE_INSENSITIVE_ORDER));

        List<Map<String, Object>> result = new ArrayList<>();
        for (McpDiscoveredPrompt prompt : ordered) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("promptId", prompt.promptId());
            item.put("name", prompt.name());
            item.put("description", prompt.description());
            item.put("argumentSchemaJson", prompt.argumentSchemaJson());
            result.add(item);
        }
        return result;
    }
}
