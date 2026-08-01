package sh.vork.oauth;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared OAuth provider template metadata.
 */
public record OAuthTemplate(
        UUID id,
        String name,
        String clientName,
        String description,
        URI authorizeEndpoint,
        URI tokenEndpoint,
        List<String> scopes,
        Map<String, String> authorizationParameters
) {
    public OAuthTemplate {
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
    }
}
