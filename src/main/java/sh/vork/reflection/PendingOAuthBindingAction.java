package sh.vork.reflection;

import java.util.Map;

import sh.vork.orm.DatabaseEntity;

/**
 * Persists a pending binding save action while OAuth redirect flow is in progress.
 */
public record PendingOAuthBindingAction(
        String uuid,
        String userUuid,
        String groupUuid,
        String originalBindingName,
        String bindingName,
        String baseUrl,
        Map<String, String> parameterValues,
        Map<String, String> secretValues,
        String copySecretsFromBindingName,
        long createdAt,
        long expiresAt) implements DatabaseEntity {

    public PendingOAuthBindingAction {
        if (originalBindingName == null) {
            originalBindingName = "";
        }
        if (bindingName == null) {
            bindingName = "";
        }
        if (baseUrl == null) {
            baseUrl = "";
        }
        if (parameterValues == null) {
            parameterValues = Map.of();
        }
        if (secretValues == null) {
            secretValues = Map.of();
        }
        if (copySecretsFromBindingName == null) {
            copySecretsFromBindingName = "";
        }
    }
}