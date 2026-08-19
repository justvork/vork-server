package sh.vork.mcp.service;

import org.springframework.stereotype.Service;
import sh.vork.mcp.client.dto.McpDiscoverResult;
import sh.vork.mcp.client.dto.McpDiscoveredPrompt;
import sh.vork.mcp.client.dto.McpDiscoveredResource;
import sh.vork.mcp.client.dto.McpDiscoveredTool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class McpContractDiffService {

    public McpContractDiff diff(McpDiscoverResult previous, McpDiscoverResult current) {
        Map<String, String> prevToolMap = toToolSignatureMap(previous.tools());
        Map<String, String> currToolMap = toToolSignatureMap(current.tools());

        Map<String, String> prevResourceMap = toResourceSignatureMap(previous.resources());
        Map<String, String> currResourceMap = toResourceSignatureMap(current.resources());

        Map<String, String> prevPromptMap = toPromptSignatureMap(previous.prompts());
        Map<String, String> currPromptMap = toPromptSignatureMap(current.prompts());

        return new McpContractDiff(
                section(prevToolMap, currToolMap),
                section(prevResourceMap, currResourceMap),
                section(prevPromptMap, currPromptMap));
    }

    private static McpContractDiffSection section(Map<String, String> previous, Map<String, String> current) {
        Set<String> previousKeys = new LinkedHashSet<>(previous.keySet());
        Set<String> currentKeys = new LinkedHashSet<>(current.keySet());

        List<String> added = currentKeys.stream().filter(k -> !previous.containsKey(k)).toList();
        List<String> removed = previousKeys.stream().filter(k -> !current.containsKey(k)).toList();
        List<String> changed = currentKeys.stream()
                .filter(previous::containsKey)
                .filter(k -> !String.valueOf(previous.get(k)).equals(String.valueOf(current.get(k))))
                .toList();

        return new McpContractDiffSection(added, removed, changed);
    }

    private static Map<String, String> toToolSignatureMap(List<McpDiscoveredTool> tools) {
        Map<String, String> map = new LinkedHashMap<>();
        for (McpDiscoveredTool tool : tools) {
            String key = tool.toolId().isBlank() ? tool.name() : tool.toolId();
            map.put(key, toSchemaSignature(tool.inputSchemaJson()));
        }
        return map;
    }

    private static String toSchemaSignature(String schema) {
        String value = schema == null ? "" : schema;
        if (value.regionMatches(true, 0, "sha256:", 0, 7)) {
            return value;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static Map<String, String> toResourceSignatureMap(List<McpDiscoveredResource> resources) {
        Map<String, String> map = new LinkedHashMap<>();
        for (McpDiscoveredResource resource : resources) {
            String key = resource.resourceId().isBlank() ? resource.name() : resource.resourceId();
            map.put(key, resource.schemaJson() + "|" + resource.uriTemplate());
        }
        return map;
    }

    private static Map<String, String> toPromptSignatureMap(List<McpDiscoveredPrompt> prompts) {
        Map<String, String> map = new LinkedHashMap<>();
        for (McpDiscoveredPrompt prompt : prompts) {
            String key = prompt.promptId().isBlank() ? prompt.name() : prompt.promptId();
            map.put(key, prompt.argumentSchemaJson());
        }
        return map;
    }

    public record McpContractDiff(
            McpContractDiffSection tools,
            McpContractDiffSection resources,
            McpContractDiffSection prompts
    ) {
        public boolean drifted() {
            return tools.hasChanges() || resources.hasChanges() || prompts.hasChanges();
        }
    }

    public record McpContractDiffSection(
            List<String> added,
            List<String> removed,
            List<String> changed
    ) {
        public boolean hasChanges() {
            return !added.isEmpty() || !removed.isEmpty() || !changed.isEmpty();
        }
    }
}
