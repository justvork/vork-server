package sh.vork.setup;

/**
 * Configuration settings for the database backend chosen during first-run setup.
 *
 * <p>{@code database} and {@code username} are MongoDB-only fields and may be
 * {@code null} when {@code backend} is {@code "redis"}.
 */
public record DatabaseSettings(
        String backend,   // "mongo" or "redis"
        String host,
        int    port,
        String database,  // MongoDB only — null for Redis
        String username,  // MongoDB only — null / blank for unauthenticated
        String password
) {}
