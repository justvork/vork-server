package sh.vork.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Placeholder credential store.
 *
 * <p>Secrets are kept in-memory only and are scoped by user + secret name.
 */
@Service
public class SecureCredentialStore {

    private final Map<SecretKey, String> secrets = new ConcurrentHashMap<>();

    public void saveSecret(VorkUser user, String name, String value) {
        SecretKey key = secretKey(user, name);
        if (value == null) {
            throw new IllegalArgumentException("Secret value must not be null");
        }
        secrets.put(key, value);
    }

    public String getSecret(VorkUser user, String name) {
        return secrets.get(secretKey(user, name));
    }

    private SecretKey secretKey(VorkUser user, String name) {
        if (user == null) {
            throw new IllegalArgumentException("User must not be null");
        }
        if (user.uuid() == null || user.uuid().isBlank()) {
            throw new IllegalArgumentException("User UUID must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Secret name must not be blank");
        }
        return new SecretKey(user.uuid(), name);
    }

    private record SecretKey(String userUuid, String name) {}
}