package sh.vork.mcp.controller.dto;

import sh.vork.mcp.model.McpTransportMode;
import sh.vork.artifact.ArtifactStatus;

public record McpBindingUpsertRequest(
        String name,
        String baseUrl,
        McpTransportMode transportMode,
        String authorization,
        String groupId,
        String artifactId,
        String version,
        ArtifactStatus artifactStatus
) {
}
