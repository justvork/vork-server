package sh.vork.mcp.controller.dto;

import sh.vork.mcp.model.McpBinding;
import sh.vork.mcp.model.McpBindingStatus;
import sh.vork.mcp.model.McpTransportMode;
import sh.vork.artifact.ArtifactStatus;

public record McpBindingResponse(
        String uuid,
        String name,
        McpBindingStatus status,
        String baseUrl,
        McpTransportMode transportMode,
        boolean authorizationConfigured,
        String groupId,
        String artifactId,
        String version,
        ArtifactStatus artifactStatus,
        long lastDiscoveredAt,
        String lastDiscoveryError,
        String lastContractHash,
        long toolCount,
        long resourceCount,
        long promptCount
) {
    public static McpBindingResponse from(McpBinding b, long toolCount, long resourceCount, long promptCount) {
        return new McpBindingResponse(
                b.uuid(),
                b.name(),
                b.status(),
                b.baseUrl(),
                b.transportMode(),
                b.authorizationSecretRef() != null && !b.authorizationSecretRef().isBlank(),
                b.groupId(),
                b.artifactId(),
                b.version(),
                b.artifactStatus(),
                b.lastDiscoveredAt(),
                b.lastDiscoveryError(),
                b.lastContractHash(),
                toolCount,
                resourceCount,
                promptCount);
    }
}
