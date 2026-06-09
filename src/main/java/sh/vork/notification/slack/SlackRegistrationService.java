package sh.vork.notification.slack;

import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProviderConfig;
import sh.vork.notification.user.UserNotificationMedia;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the in-memory state for user DM registration with the Slack bot.
 *
 * <p>The registration flow:
 * <ol>
 *   <li>The user's browser calls
 *       {@code POST /api/user/notification-media/slack/register} — this generates a
 *       one-time code and stores a {@link PendingRegistration}.</li>
 *   <li>The user opens a Slack DM with the bot and sends {@code register CODE}.</li>
 *   <li>{@link SlackDmRegistrationConsumer} calls
 *       {@link #complete(String, String, String, String)} which saves a
 *       {@link UserNotificationMedia} and marks the registration complete.</li>
 *   <li>The browser polls {@link #checkStatus(String)} until {@code "complete"}.</li>
 * </ol>
 *
 * <p>Pending registrations expire after {@value #EXPIRY_MINUTES} minutes.
 */
@Service
public class SlackRegistrationService {

    private static final Logger   log            = LoggerFactory.getLogger(SlackRegistrationService.class);
    private static final Duration EXPIRY         = Duration.ofMinutes(15);
    private static final int      EXPIRY_MINUTES = 15;

    private final DatabaseRepository<NotificationProviderConfig> configRepo;
    private final DatabaseRepository<UserNotificationMedia>      mediaRepo;

    /** registrationId → pending */
    private final ConcurrentHashMap<String, PendingRegistration> byId   = new ConcurrentHashMap<>();
    /** code → pending */
    private final ConcurrentHashMap<String, PendingRegistration> byCode = new ConcurrentHashMap<>();

    public SlackRegistrationService(
            DatabaseRepository<NotificationProviderConfig> configRepo,
            DatabaseRepository<UserNotificationMedia> mediaRepo) {
        this.configRepo = configRepo;
        this.mediaRepo  = mediaRepo;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts a new DM registration session for {@code userId}.
     *
     * @param userId           the Vork account user ID
     * @param providerConfigId the Slack {@link NotificationProviderConfig} UUID
     * @param isDefault        whether this media should become the user's default
     * @return registration info containing the code and instructions for the user
     * @throws IllegalArgumentException if the config is not found or not a Slack config
     */
    public RegistrationInfo startRegistration(String userId, String providerConfigId,
                                               boolean isDefault) {
        log.debug("ENTER startRegistration: [userId={}, configId={}]", userId, providerConfigId);

        NotificationProviderConfig config = configRepo.get(providerConfigId);
        if (config == null || !"slack".equals(config.providerKey())) {
            throw new IllegalArgumentException("Slack provider config not found: " + providerConfigId);
        }

        String code           = UUID.randomUUID().toString().replace("-", "")
                                    .substring(0, 16).toUpperCase();
        String registrationId = UUID.randomUUID().toString();
        String instructions   = "Open a DM with the Vork bot in Slack and send:\nregister " + code
                + "\n\nThis code expires in " + EXPIRY_MINUTES + " minutes.";

        PendingRegistration pending = new PendingRegistration(
                registrationId, userId, providerConfigId, code, isDefault, Instant.now());
        byId.put(registrationId, pending);
        byCode.put(code, pending);

        log.info("Slack DM registration started [userId={}, regId={}]", userId, registrationId);
        return new RegistrationInfo(registrationId, instructions);
    }

    /**
     * Called by {@link SlackDmRegistrationConsumer} when {@code register CODE} is
     * received in a Slack DM.
     *
     * @param configId  the provider config ID for the receiving bot
     * @param code      the code the user typed
     * @param slackUserId the Slack member ID of the sending user
     * @param botToken  the bot token (not stored but may be used for future features)
     * @return {@code true} if registration succeeded; {@code false} if the code is
     *         unknown or expired
     */
    public boolean complete(String configId, String code, String slackUserId, String botToken) {
        log.debug("ENTER complete: [code={}, slackUserId={}, configId={}]",
                code, slackUserId, configId);

        PendingRegistration pending = byCode.get(code);
        if (pending == null) {
            log.debug("No pending registration for code");
            return false;
        }
        if (!pending.providerConfigId.equals(configId)) {
            log.debug("Code belongs to a different configId — ignoring");
            return false;
        }
        if (Instant.now().isAfter(pending.createdAt.plus(EXPIRY))) {
            byCode.remove(code);
            byId.remove(pending.registrationId);
            log.debug("Slack DM registration code expired [code={}]", code);
            return false;
        }
        if (pending.complete) return true; // idempotent

        boolean makeDefault = pending.isDefault
                || mediaRepo.searchCount(SearchQuery.eq("userId", pending.userId)) == 0;
        if (makeDefault) clearDefaults(pending.userId);

        UserNotificationMedia media = new UserNotificationMedia(
                UUID.randomUUID().toString(),
                pending.userId,
                pending.providerConfigId,
                NotificationMediaType.SLACK,
                slackUserId,
                "Slack DM",
                makeDefault,
                false,
                System.currentTimeMillis());
        mediaRepo.save(media);

        pending.markComplete(media.uuid());
        byCode.remove(code);

        log.info("Slack DM registration completed [userId={}, slackUserId={}]",
                pending.userId, slackUserId);
        return true;
    }

    /**
     * Polls the status of a pending registration.
     *
     * @return never {@code null}; status is {@code "pending"}, {@code "complete"},
     *         or {@code "expired"}
     */
    public RegistrationStatus checkStatus(String registrationId) {
        PendingRegistration pending = byId.get(registrationId);
        if (pending == null) return new RegistrationStatus("expired", null);

        if (Instant.now().isAfter(pending.createdAt.plus(EXPIRY))) {
            byId.remove(registrationId);
            byCode.remove(pending.code);
            return new RegistrationStatus("expired", null);
        }
        if (pending.complete) {
            byId.remove(registrationId);
            return new RegistrationStatus("complete", pending.mediaId);
        }
        return new RegistrationStatus("pending", null);
    }

    /**
     * Cancels a pending registration (user dismissed the modal).
     * No-op if the registration ID is unknown.
     */
    public void cancel(String registrationId) {
        PendingRegistration pending = byId.remove(registrationId);
        if (pending != null) {
            byCode.remove(pending.code);
            log.debug("Slack DM registration cancelled [regId={}]", registrationId);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void clearDefaults(String userId) {
        try (var stream = mediaRepo.search(0, Integer.MAX_VALUE, "createdAt",
                com.jadaptive.orm.SortOrder.ASC,
                SearchQuery.eq("userId", userId),
                SearchQuery.eq("isDefault", true))) {
            stream.forEach(m -> mediaRepo.save(new UserNotificationMedia(
                    m.uuid(), m.userId(), m.providerKey(),
                    m.mediaType(), m.address(), m.label(),
                    false, m.oobEnabled(), m.createdAt())));
        }
    }

    // ── Value types ───────────────────────────────────────────────────────────

    public record RegistrationInfo(String registrationId, String instructions) {}

    public record RegistrationStatus(String status, String mediaId) {}

    private static class PendingRegistration {
        final String  registrationId;
        final String  userId;
        final String  providerConfigId;
        final String  code;
        final boolean isDefault;
        final Instant createdAt;

        volatile boolean complete = false;
        volatile String  mediaId  = null;

        PendingRegistration(String registrationId, String userId, String providerConfigId,
                             String code, boolean isDefault, Instant createdAt) {
            this.registrationId  = registrationId;
            this.userId          = userId;
            this.providerConfigId = providerConfigId;
            this.code            = code;
            this.isDefault       = isDefault;
            this.createdAt       = createdAt;
        }

        void markComplete(String mediaId) {
            this.mediaId  = mediaId;
            this.complete = true;
        }
    }
}
