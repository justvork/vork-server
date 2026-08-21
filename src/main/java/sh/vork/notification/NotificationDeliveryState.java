package sh.vork.notification;

/**
 * Final state of a single notification delivery ledger entry.
 */
public enum NotificationDeliveryState {
    SENT,
    FAILED,
    ALREADY_SENT
}
