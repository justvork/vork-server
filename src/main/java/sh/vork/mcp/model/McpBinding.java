package sh.vork.mcp.model;

import sh.vork.orm.DatabaseEntity;
import sh.vork.reflection.ArtifactStatus;

/**
 * Binding configuration for one MCP server endpoint.
 */
public record McpBinding(
        String uuid,
        String name,
        McpBindingStatus status,
        String baseUrl,
        McpTransportMode transportMode,
        String authorizationSecretRef,
        String groupId,
        String artifactId,
        String version,
        ArtifactStatus artifactStatus,
        long lastDiscoveredAt,
        String lastDiscoveryError,
        String lastContractHash,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public McpBinding {
        if (name == null || name.isBlank()) {
            name = "Unnamed MCP Binding";
        }
        if (status == null) {
            status = McpBindingStatus.INACTIVE;
        }
        if (baseUrl == null) {
            baseUrl = "";
        }
        if (transportMode == null) {
            transportMode = McpTransportMode.STREAMABLE_HTTP;
        }
        if (authorizationSecretRef == null) {
            authorizationSecretRef = "";
        }
        if (groupId == null) {
            groupId = "";
        }
        if (artifactId == null) {
            artifactId = "";
        }
        if (version == null || version.isBlank()) {
            version = "SNAPSHOT";
        }
        if (artifactStatus == null) {
            artifactStatus = ArtifactStatus.SNAPSHOT;
        }
        if (lastDiscoveryError == null) {
            lastDiscoveryError = "";
        }
        if (lastContractHash == null) {
            lastContractHash = "";
        }
    }
}
