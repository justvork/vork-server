package sh.vork.security;

import java.util.UUID;

import org.springframework.stereotype.Service;

import java.util.stream.Stream;

import sh.vork.ai.security.encrypt.EncryptionService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;

/**
 * Placeholder credential store.
 *
 * <p>Secrets are kept in-memory only and are scoped by user + secret name.
 */
@Service
public class SecureCredentialStore {

    private final EncryptionService encryptionService;
    private final UserService userService;

    private final  DatabaseRepository<Secret> secretRepository;

    public SecureCredentialStore( RepositoryFactory factory,
                                  EncryptionService encryptionService,
                                  UserService userService ) {
        this.secretRepository = factory.create(Secret.class);
        this.encryptionService = encryptionService;
        this.userService = userService;
    }
    public void saveSecret(VorkUser user, String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException("Secret value must not be null");
        }

        String uuid = UUID.nameUUIDFromBytes((user.uuid() + ":" + key).getBytes()).toString();
        
        // Check if this is an update by looking for existing secret
        Secret existing = secretRepository.get(uuid);
        long now = System.currentTimeMillis();
        long createdAt = existing != null ? existing.createdAt() : now;
        long updatedAt = now;
        
        secretRepository.save(new Secret(
            uuid,
            user.uuid(),
            key,
            encryptionService.encrypt(value),
            createdAt,
            updatedAt
        ));
    }

    public String getSecret(VorkUser user, String key) {
        Secret secret = secretRepository.get(
            SearchQuery.eq("userUuid", user.uuid()),
            SearchQuery.eq("key", key));

        if (secret == null) {
            return null;
        }
        return encryptionService.decrypt(secret.encryptedPayload());
    }

    public void deleteSecret(VorkUser user, String key) {
        String uuid = UUID.nameUUIDFromBytes((user.uuid() + ":" + key).getBytes()).toString();
        secretRepository.delete(uuid);
    }

    // ── Username-based convenience methods (used by skill secret gate) ────────

    /**
     * Returns {@code true} when the named secret is present for the given username.
     * Uses the same storage layout as {@link #saveSecret(VorkUser, String, String)}.
     */
    public boolean hasSecret(String username, String key) {
        return getSecretForUser(username, key) != null;
    }

    /**
     * Returns the decrypted secret value for the given username and key,
     * or {@code null} when not found.
     */
    public String getSecretForUser(String username, String key) {
        return getSecret(userService.getRequiredEnabledUser(username), key);
    }

    /**
     * Saves a secret keyed by username string directly, without a full {@link VorkUser} object.
     */
    public void saveSecretForUser(String username, String key, String value) {
        saveSecret(userService.getRequiredEnabledUser(username), key, value);
    }

    // ── List and count methods (used by Secrets Manager REST API) ────────────

    /**
     * Returns a lazily-loaded stream of all secrets for the given user, paged and sorted
     * by creation time (descending). The returned entities include encrypted payloads only.
     * Callers must close the stream to release the database cursor.
     */
    public Stream<Secret> getSecretsForUser(VorkUser user, int page, int pageSize) {
        return secretRepository.search(page, pageSize, "uuid", SortOrder.DESC,
                SearchQuery.eq("userUuid", user.uuid()));
    }

    /**
     * Returns the total count of secrets for the given user.
     */
    public long countSecretsForUser(VorkUser user) {
        return secretRepository.searchCount(SearchQuery.eq("userUuid", user.uuid()));
    }

    /**
     * Returns the metadata (uuid, key, timestamps) for a single secret without decryption.
     * Returns {@code null} if the secret is not found.
     */
    public Secret getSecretMetadata(VorkUser user, String key) {
        return secretRepository.get(
                SearchQuery.eq("userUuid", user.uuid()),
                SearchQuery.eq("key", key));
    }
}