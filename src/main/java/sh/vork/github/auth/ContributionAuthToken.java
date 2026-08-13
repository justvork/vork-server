package sh.vork.github.auth;

/**
 * Normalized auth context consumed by contribution workflows.
 *
 * <p>This contract intentionally hides provider-specific details so additional
 * authentication mechanisms can be added without changing contribution logic.
 */
public record ContributionAuthToken(
        String provider,
        String localUsername,
        String externalUsername,
        String accessToken,
        long expiresAt
) {
}
