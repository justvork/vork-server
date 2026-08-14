package sh.vork.hub.repository;

import java.net.URI;

/**
 * Resolved repository definition for Hub discovery/install sources.
 */
public record HubRepositoryDefinition(
        String name,
        URI baseUrl,
        boolean readOnly,
        boolean available,
        String message
) {
}
