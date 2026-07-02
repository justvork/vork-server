package sh.vork.security;

/**
 * Canonical role names for persisted users.
 */
public enum UserRole {
    ADMIN,
    USER;

    public static UserRole fromStoredValue(String value) {
        if (value == null || value.isBlank()) {
            return USER;
        }
        String normalized = value.startsWith("ROLE_") ? value.substring("ROLE_".length()) : value;
        try {
            return UserRole.valueOf(normalized.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return USER;
        }
    }

    public String authority() {
        return "ROLE_" + name();
    }
}
