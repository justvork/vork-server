package sh.vork.security;

import sh.vork.orm.DatabaseEntity;

/**
 * User entity for persistent credential storage.
 * Persisted to MongoDB collection: vork_user
 */
public record VorkUser(
    String uuid,           // username as unique ID
    String displayName,    // optional human-readable display name
    String passwordHash,   // BCrypt-encoded password
    String role,           // ADMIN, USER, etc.
    Boolean enabled,       // null means enabled for legacy records
    long createdAt,
    long updatedAt
) implements DatabaseEntity {
    public VorkUser {
        if (displayName == null) {
            displayName = "";
        }
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }
}
