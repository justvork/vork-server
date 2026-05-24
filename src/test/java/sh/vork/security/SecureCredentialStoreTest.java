package sh.vork.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SecureCredentialStoreTest {

    private SecureCredentialStore store;

    @BeforeEach
    void setUp() {
        store = new SecureCredentialStore();
    }

    @Test
    void savesAndGetsSecretForUserAndName() {
        VorkUser user = new VorkUser("alice", "hash", "USER", 0L, 0L);

        store.saveSecret(user, "apiKey", "secret-123");

        assertEquals("secret-123", store.getSecret(user, "apiKey"));
    }

    @Test
    void returnsNullWhenSecretDoesNotExist() {
        VorkUser user = new VorkUser("alice", "hash", "USER", 0L, 0L);

        assertNull(store.getSecret(user, "missing"));
    }

    @Test
    void scopesSecretsByUserAndName() {
        VorkUser alice = new VorkUser("alice", "hash", "USER", 0L, 0L);
        VorkUser bob = new VorkUser("bob", "hash", "USER", 0L, 0L);

        store.saveSecret(alice, "token", "alice-token");
        store.saveSecret(bob, "token", "bob-token");
        store.saveSecret(alice, "other", "alice-other");

        assertEquals("alice-token", store.getSecret(alice, "token"));
        assertEquals("bob-token", store.getSecret(bob, "token"));
        assertEquals("alice-other", store.getSecret(alice, "other"));
    }
}