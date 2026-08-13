package sh.vork.github.auth;

import sh.vork.orm.DatabaseEntity;

/**
 * Persisted device-flow challenge state for a single authenticated user.
 */
public record GitHubDeviceFlowSession(
        String uuid,
        String userUuid,
        String deviceCode,
        String userCode,
        String verificationUri,
        String verificationUriComplete,
        int intervalSeconds,
        long expiresAt,
        long createdAt,
        long updatedAt,
        String status,
        String error,
        String githubLogin,
        long tokenExpiresAt
) implements DatabaseEntity {
}
