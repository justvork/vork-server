package sh.vork.security;

/**
 * Fine-grained permissions used by endpoint and tool authorization checks.
 */
public enum Permission {
    USERS_MANAGE,
    AGENTS_WRITE,
    SKILLS_WRITE,
    TYPES_WRITE;

    public String authority() {
        return name();
    }
}
