package sh.vork.notification;

import java.util.List;

import sh.vork.orm.DatabaseEntity;

/**
 * Immutable ledger entry for one direct-notification send attempt.
 */
public record NotificationLedgerEntry(
        String uuid,
        String logicalNotificationId,
        String idempotencyGroup,
        String idempotencyKey,
        String mediaType,
        String destination,
        String title,
        String bodyHash,
        String originatingAgent,
        String originatingSessionUuid,
        String originatingSkill,
        String createdBy,
        long createdAt,
        NotificationDeliveryState finalState,
        String providerConfigId,
        String providerKey,
        String providerMessageReferenceId,
        String errorMessage,
        List<NotificationProviderAttempt> providerAttempts
) implements DatabaseEntity {

    public NotificationLedgerEntry {
        if (providerAttempts == null) {
            providerAttempts = List.of();
        }
    }
}
