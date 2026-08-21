package sh.vork.security;

import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import sh.vork.ai.security.encrypt.EncryptionService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.RepositoryFactory;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;

/**
 * Credential store for encrypted secrets.
 *
 * <p>Secrets are primarily user-scoped, with optional admin-managed global secrets
 * for shared runtime integrations.
 */
@Service
public class SecureCredentialStore {

    private static final String GLOBAL_SECRET_OWNER_UUID = "__GLOBAL__";

    private final EncryptionService encryptionService;
    private final UserService userService;
    private final DatabaseRepository<Secret> secretRepository;

    public SecureCredentialStore(RepositoryFactory factory,
                                 EncryptionService encryptionService,
                                 UserService userService) {
        this.secretRepository = factory.create(Secret.class);
        this.encryptionService = encryptionService;
        this.userService = userService;
    }

    public void saveSecret(VorkUser user, String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException("Secret value must not be null");
        }

        String normalizedKey = normalizeSecretKey(key);
        if (normalizedKey.isBlank()) {
            throw new IllegalArgumentException("Secret key must not be blank");
        }

        String uuid = UUID.nameUUIDFromBytes((user.uuid() + ":" + normalizedKey).getBytes()).toString();
        Secret existing = secretRepository.get(uuid);
        long now = System.currentTimeMillis();
        long createdAt = existing != null ? existing.createdAt() : now;

        secretRepository.save(new Secret(
                uuid,
                user.uuid(),
                normalizedKey,
                encryptionService.encrypt(value),
                createdAt,
                now
        ));
    }

    public String getSecret(VorkUser user, String key) {
        Secret secret = findSecretForUser(user, key);
        if (secret == null) {
            return null;
        }
        return encryptionService.decrypt(secret.encryptedPayload());
    }

    public void deleteSecret(VorkUser user, String key) {
        Secret secret = findSecretForUser(user, key);
        if (secret != null) {
            secretRepository.delete(secret.uuid());
        }
    }

    // -- Global secrets (admin-managed) -------------------------------------

    public void saveGlobalSecret(String key, String value) {
        if (value == null) {
            throw new IllegalArgumentException("Secret value must not be null");
        }

        String normalizedKey = normalizeSecretKey(key);
        if (normalizedKey.isBlank()) {
            throw new IllegalArgumentException("Secret key must not be blank");
        }

        String uuid = UUID.nameUUIDFromBytes((GLOBAL_SECRET_OWNER_UUID + ":" + normalizedKey).getBytes()).toString();
        Secret existing = secretRepository.get(uuid);
        long now = System.currentTimeMillis();
        long createdAt = existing != null ? existing.createdAt() : now;

        secretRepository.save(new Secret(
                uuid,
                GLOBAL_SECRET_OWNER_UUID,
                normalizedKey,
                encryptionService.encrypt(value),
                createdAt,
                now
        ));
    }

    public String getGlobalSecret(String key) {
        Secret secret = findGlobalSecret(key);
        if (secret == null) {
            return null;
        }
        return encryptionService.decrypt(secret.encryptedPayload());
    }

    public void deleteGlobalSecret(String key) {
        Secret secret = findGlobalSecret(key);
        if (secret != null) {
            secretRepository.delete(secret.uuid());
        }
    }

    public Stream<Secret> getGlobalSecrets(int page, int pageSize) {
        return secretRepository.search(page, pageSize, "uuid", SortOrder.DESC,
                SearchQuery.eq("userUuid", GLOBAL_SECRET_OWNER_UUID));
    }

    public long countGlobalSecrets() {
        return secretRepository.searchCount(SearchQuery.eq("userUuid", GLOBAL_SECRET_OWNER_UUID));
    }

    public Secret getGlobalSecretMetadata(String key) {
        return findGlobalSecret(key);
    }

    // -- Username convenience methods ---------------------------------------

    public boolean hasSecret(String username, String key) {
        return getSecretForUser(username, key) != null;
    }

    public String getSecretForUser(String username, String key) {
        return getSecret(userService.getRequiredEnabledUser(username), key);
    }

    /**
     * Returns user-scoped secret first; falls back to global secret.
     */
    public String getSecretForUserWithGlobalFallback(String username, String key) {
        String userScoped = getSecretForUser(username, key);
        if (userScoped != null) {
            return userScoped;
        }
        return getGlobalSecret(key);
    }

    public void saveSecretForUser(String username, String key, String value) {
        saveSecret(userService.getRequiredEnabledUser(username), key, value);
    }

    // -- List and count methods (used by Secrets Manager REST API) ----------

    public Stream<Secret> getSecretsForUser(VorkUser user, int page, int pageSize) {
        return secretRepository.search(page, pageSize, "uuid", SortOrder.DESC,
                SearchQuery.eq("userUuid", user.uuid()));
    }

    public long countSecretsForUser(VorkUser user) {
        return secretRepository.searchCount(SearchQuery.eq("userUuid", user.uuid()));
    }

    public Secret getSecretMetadata(VorkUser user, String key) {
        return findSecretForUser(user, key);
    }

    private Secret findSecretForUser(VorkUser user, String key) {
        if (user == null || key == null) {
            return null;
        }

        String normalizedKey = normalizeSecretKey(key);
        if (normalizedKey.isBlank()) {
            return null;
        }

        Secret direct = secretRepository.get(
                SearchQuery.eq("userUuid", user.uuid()),
                SearchQuery.eq("key", normalizedKey));
        if (direct != null) {
            return direct;
        }

        // Backward compatibility: resolve legacy keys saved before normalization.
        try (Stream<Secret> stream = getSecretsForUser(user, 0, Integer.MAX_VALUE)) {
            return stream
                    .filter(secret -> secret != null && secret.key() != null)
                    .filter(secret -> normalizedKey.equals(normalizeSecretKey(secret.key())))
                    .findFirst()
                    .orElse(null);
        }
    }

    private Secret findGlobalSecret(String key) {
        if (key == null) {
            return null;
        }

        String normalizedKey = normalizeSecretKey(key);
        if (normalizedKey.isBlank()) {
            return null;
        }

        Secret direct = secretRepository.get(
                SearchQuery.eq("userUuid", GLOBAL_SECRET_OWNER_UUID),
                SearchQuery.eq("key", normalizedKey));
        if (direct != null) {
            return direct;
        }

        // Backward compatibility: resolve legacy global keys saved before normalization.
        try (Stream<Secret> stream = getGlobalSecrets(0, Integer.MAX_VALUE)) {
            return stream
                    .filter(secret -> secret != null && secret.key() != null)
                    .filter(secret -> normalizedKey.equals(normalizeSecretKey(secret.key())))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static String normalizeSecretKey(String key) {
        if (key == null) {
            return "";
        }

        String normalized = key.trim();
        if (normalized.startsWith("{{") && normalized.endsWith("}}") && normalized.length() > 4) {
            normalized = normalized.substring(2, normalized.length() - 2).trim();
        }
        normalized = normalized.replace('-', '_').replace('.', '_').replace(' ', '_');
        return normalized.toUpperCase(Locale.ROOT);
    }
}
