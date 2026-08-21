package sh.vork.notification;

/**
 * Provider-level attempt metadata captured within a notification ledger entry.
 */
public record NotificationProviderAttempt(
        int attemptNumber,
        long attemptedAt,
        NotificationDeliveryState state,
        String providerConfigId,
        String providerKey,
        String providerMessageReferenceId,
        String errorMessage
) {
}
