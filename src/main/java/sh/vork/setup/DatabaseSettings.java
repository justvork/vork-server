package sh.vork.setup;

/**
 * Configuration settings for the database backend chosen during first-run setup.
 *
 * <p>{@code database}, {@code username}, and {@code uri} are backend-specific fields:
 * <ul>
 *   <li>MongoDB: {@code uri} is the full MongoDB connection string.</li>
 *   <li>Couchbase: {@code database} maps to the bucket name.</li>
 *   <li>Nitrite: {@code database} maps to the embedded file path.</li>
 *   <li>Redis: {@code database}, {@code username}, and {@code uri} are unused.</li>
 * </ul>
 */
public record DatabaseSettings(
        String backend,   // "nitrite", "mongo", "redis", or "couchbase"
        String host,
        int    port,
        String database,  // Couchbase bucket / Nitrite file path
        String username,  // Couchbase username
        String password,
        String uri        // MongoDB connection URI
) {
    public DatabaseSettings(String backend, String host, int port,
                            String database, String username, String password) {
        this(backend, host, port, database, username, password, null);
    }
}
