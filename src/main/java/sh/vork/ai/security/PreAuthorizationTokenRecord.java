package sh.vork.ai.security;

import sh.vork.orm.DatabaseEntity;

/**
 * One-time pre-authorization token bound to an exact tool call payload.
 */
public record PreAuthorizationTokenRecord(
        String uuid,
        String username,
        String sessionUuid,
        String toolName,
        String canonicalArguments,
        String argumentsSha256,
        String scope,
        String status,
        long createdAt,
        long expiresAt,
        Long consumedAt,
        String issuedReason
) implements DatabaseEntity {
}
