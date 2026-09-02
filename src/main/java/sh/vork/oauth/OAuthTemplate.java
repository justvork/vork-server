package sh.vork.oauth;

import sh.vork.artifact.ArtifactStatus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Shared OAuth provider template metadata.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OAuthTemplate(
    String id,
        String name,
        String clientName,
        String description,
        URI authorizeEndpoint,
        URI tokenEndpoint,
        List<String> scopes,
    Map<String, String> authorizationParameters,
    ArtifactStatus artifactStatus
) {
    public OAuthTemplate {
        if (id != null) {
            id = id.trim();
            if (id.isBlank()) {
                id = null;
            }
        }
        if (clientName == null || clientName.isBlank()) {
            clientName = OAuthClientService.normalizeClientName(name);
        }
        if (description == null) {
            description = "";
        }
        if (scopes == null) {
            scopes = List.of();
        }
        if (authorizationParameters == null) {
            authorizationParameters = Map.of();
        }
        artifactStatus = artifactStatus == null ? ArtifactStatus.SNAPSHOT : artifactStatus;
    }

    public boolean isSnapshotMutable() {
        return artifactStatus == ArtifactStatus.SNAPSHOT || artifactStatus == ArtifactStatus.REJECTED;
    }
}
