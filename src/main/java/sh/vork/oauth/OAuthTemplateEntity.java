package sh.vork.oauth;

import sh.vork.artifact.ArtifactStatus;

import java.util.List;
import java.util.Map;
import sh.vork.orm.DatabaseEntity;

/**
 * Persisted OAuth template document.
 */
public record OAuthTemplateEntity(
        String uuid,
        String name,
    String clientName,
        String description,
        String authorizeEndpoint,
        String tokenEndpoint,
        List<String> scopes,
        Map<String, String> authorizationParameters,
        ArtifactStatus artifactStatus,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public OAuthTemplateEntity {
        if (name == null) {
            name = "";
        }
        if (clientName == null) {
            clientName = "";
        }
        if (description == null) {
            description = "";
        }
        if (authorizeEndpoint == null) {
            authorizeEndpoint = "";
        }
        if (tokenEndpoint == null) {
            tokenEndpoint = "";
        }
        if (scopes == null) {
            scopes = List.of();
        }
        if (authorizationParameters == null) {
            authorizationParameters = Map.of();
        }
        artifactStatus = artifactStatus == null ? ArtifactStatus.SNAPSHOT : artifactStatus;
    }
}
