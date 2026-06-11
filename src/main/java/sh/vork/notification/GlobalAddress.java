package sh.vork.notification;

import sh.vork.orm.DatabaseEntity;

/**
 * A system-level (admin-configured) notification destination shared across all users.
 *
 * <p>Unlike {@link sh.vork.notification.user.UserNotificationMedia} which is
 * per-user, a {@code GlobalAddress} is attached to a
 * {@link NotificationProviderConfig} and can be used to deliver notifications to
 * shared targets such as Telegram groups, mailing lists, or shared phone numbers.
 *
 * @param uuid             surrogate UUID — primary key
 * @param providerConfigId UUID of the {@link NotificationProviderConfig} that owns this address
 * @param label            friendly name (e.g. "Infrastructure Alerts Group")
 * @param mediaType        the type of address ({@link NotificationMediaType})
 * @param address          the actual address: email, E.164 phone, or Telegram chat ID
 * @param createdAt        epoch-millis creation timestamp
 */
public record GlobalAddress(
        String               uuid,
        String               providerConfigId,
        String               label,
        NotificationMediaType mediaType,
        String               address,
        long                 createdAt
) implements DatabaseEntity {}
