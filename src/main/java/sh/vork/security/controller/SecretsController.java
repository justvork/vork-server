package sh.vork.security.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import sh.vork.security.Secret;
import sh.vork.security.SecureCredentialStore;
import sh.vork.security.UserService;
import sh.vork.security.VorkUser;

/**
 * REST API for user secrets management.
 *
 * <p>All endpoints require authentication. Secrets are never decrypted in API responses;
 * they are always redacted as "••••" to prevent accidental exposure.
 */
@RestController
@RequestMapping("/api/secrets")
@PreAuthorize("isAuthenticated()")
public class SecretsController {

    private static final Logger log = LoggerFactory.getLogger(SecretsController.class);

    private final SecureCredentialStore credentialStore;
    private final UserService userService;

    public SecretsController(SecureCredentialStore credentialStore, UserService userService) {
        this.credentialStore = credentialStore;
        this.userService = userService;
    }

    /**
     * GET /api/secrets — List all secrets for the current user, with values redacted.
     *
     * @param page     zero-based page number (default: 0)
     * @param pageSize number of results per page (default: 20)
     * @return JSON object with redacted secrets array and total count
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> listSecrets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        try {
            log.debug("ENTER listSecrets: page={}, pageSize={}", page, pageSize);
            VorkUser user = getCurrentUser();

            try (Stream<Secret> stream = credentialStore.getSecretsForUser(user, page, pageSize)) {
                List<Map<String, Object>> redacted = stream.map(secret -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("key", secret.key());
                    item.put("value", "••••");
                    item.put("createdAt", secret.uuid()); // Use uuid as stable identifier
                    return item;
                }).toList();

                long total = credentialStore.countSecretsForUser(user);

                Map<String, Object> response = new HashMap<>();
                response.put("secrets", redacted);
                response.put("total", total);
                response.put("page", page);
                response.put("pageSize", pageSize);

                log.debug("EXIT listSecrets: returning {} secrets", redacted.size());
                return ResponseEntity.ok(response);
            }
        } catch (Exception e) {
            log.error("Error in listSecrets", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    /**
     * POST /api/secrets — Create a new secret for the current user.
     *
     * @param request JSON body with "key" and "value" fields
     * @return JSON object with status and key
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createSecret(@RequestBody CreateSecretRequest request) {
        try {
            log.debug("ENTER createSecret: key={}", request.key());
            if (request.key() == null || request.key().isEmpty()) {
                throw new IllegalArgumentException("Key cannot be empty");
            }
            if (request.value() == null || request.value().isEmpty()) {
                throw new IllegalArgumentException("Value cannot be empty");
            }

            VorkUser user = getCurrentUser();
            credentialStore.saveSecret(user, request.key(), request.value());

            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");
            response.put("key", request.key());

            log.debug("EXIT createSecret: key={}", request.key());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error in createSecret", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.status(400).body(error);
        }
    }

    /**
     * PUT /api/secrets/{key} — Update an existing secret for the current user.
     *
     * @param key     the secret key to update
     * @param request JSON body with "value" field
     * @return JSON object with status and key
     */
    @PutMapping("/{key}")
    public ResponseEntity<Map<String, Object>> updateSecret(
            @PathVariable String key,
            @RequestBody UpdateSecretRequest request) {

        try {
            log.debug("ENTER updateSecret: key={}", key);
            if (request.value() == null || request.value().isEmpty()) {
                throw new IllegalArgumentException("Value cannot be empty");
            }

            VorkUser user = getCurrentUser();

            // Verify the secret exists
            Secret existing = credentialStore.getSecretMetadata(user, key);
            if (existing == null) {
                throw new IllegalArgumentException("Secret not found: " + key);
            }

            credentialStore.saveSecret(user, key, request.value());

            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");
            response.put("key", key);

            log.debug("EXIT updateSecret: key={}", key);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error in updateSecret", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.status(400).body(error);
        }
    }

    /**
     * DELETE /api/secrets/{key} — Delete a secret for the current user.
     *
     * @param key the secret key to delete
     * @return JSON object with status
     */
    @DeleteMapping("/{key}")
    public ResponseEntity<Map<String, Object>> deleteSecret(@PathVariable String key) {
        try {
            log.debug("ENTER deleteSecret: key={}", key);
            VorkUser user = getCurrentUser();
            credentialStore.deleteSecret(user, key);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "ok");

            log.debug("EXIT deleteSecret: key={}", key);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error in deleteSecret", e);
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    /**
     * Extracts the current authenticated user from the security context.
     */
    private VorkUser getCurrentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            log.warn("User not authenticated");
            throw new SecurityException("User not authenticated");
        }

        String username = auth.getName();
        if (username == null || username.isBlank()) {
            log.warn("Username not found in authentication");
            throw new SecurityException("Username not found in authentication");
        }

        log.debug("Getting user for username: {}", username);
        VorkUser user = userService.getRequiredEnabledUser(username);
        log.debug("Got user: {}", user.uuid());
        return user;
    }

    // ── Request DTOs ──────────────────────────────────────────────────────────

    /**
     * Request body for creating a secret.
     */
    public record CreateSecretRequest(
            String key,
            String value
    ) {}

    /**
     * Request body for updating a secret.
     */
    public record UpdateSecretRequest(
            String value
    ) {}
}
