package sh.vork.notification.telegram;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jadaptive.orm.DatabaseRepository;

import sh.vork.notification.GlobalAddress;
import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProviderConfig;

/**
 * Manages the Telegram group registration flow for {@link GlobalAddress} entries.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>Admin clicks "Add Telegram Group" in the UI → calls {@link #startGroupRegistration}
 *       which returns a one-time code and instructions.</li>
 *   <li>Admin adds the bot to their Telegram group and types {@code /register CODE}.</li>
 *   <li>{@link TelegramGroupRegistrationConsumer} calls {@link #complete} with the code,
 *       group chat ID, and chat title.</li>
 *   <li>UI polling via {@link #checkStatus} detects completion and shows the new address.</li>
 * </ol>
 *
 * <p>Pending registrations expire after 15 minutes.  State is held entirely in
 * memory; a server restart cancels any open registrations.
 */
@Service
public class TelegramGroupRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(TelegramGroupRegistrationService.class);
    private static final Duration EXPIRY = Duration.ofMinutes(15);

    /** registrationId → pending state */
    private final ConcurrentHashMap<String, PendingGroupRegistration> byId   = new ConcurrentHashMap<>();
    /** one-time code → pending state */
    private final ConcurrentHashMap<String, PendingGroupRegistration> byCode = new ConcurrentHashMap<>();

    private final DatabaseRepository<NotificationProviderConfig> configRepo;
    private final DatabaseRepository<GlobalAddress>              globalAddressRepo;

    public TelegramGroupRegistrationService(
            DatabaseRepository<NotificationProviderConfig> configRepo,
            DatabaseRepository<GlobalAddress> globalAddressRepo) {
        this.configRepo        = configRepo;
        this.globalAddressRepo = globalAddressRepo;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts a new group registration for the given Telegram provider config.
     *
     * @param providerConfigId UUID of a saved Telegram {@link NotificationProviderConfig}
     * @return metadata the UI needs to display registration instructions
     * @throws IllegalArgumentException if the config is missing or not a Telegram provider
     */
    public GroupRegistrationInfo startGroupRegistration(String providerConfigId) {
        log.debug("ENTER startGroupRegistration: [configId={}]", providerConfigId);

        NotificationProviderConfig config = configRepo.get(providerConfigId);
        if (config == null || !"telegram".equals(config.providerKey())) {
            throw new IllegalArgumentException(
                    "Telegram provider config not found: " + providerConfigId);
        }

        // Purge expired entries before adding a new one
        purgeExpired();

        String code           = UUID.randomUUID().toString().replace("-", "")
                                    .substring(0, 16).toUpperCase();
        String registrationId = UUID.randomUUID().toString();

        PendingGroupRegistration pending = new PendingGroupRegistration(
                registrationId, providerConfigId, code, Instant.now(), null);
        byId.put(registrationId, pending);
        byCode.put(code, pending);

        log.info("Telegram group registration started [configId={}, regId={}]",
                providerConfigId, registrationId);
        return new GroupRegistrationInfo(registrationId, code);
    }

    /**
     * Called by {@link TelegramGroupRegistrationConsumer} when a
     * {@code /register CODE} message is received inside a group.
     *
     * @param configId  UUID of the provider config that received the message
     * @param code      the registration code extracted from the message
     * @param chatId    Telegram chat ID of the group (negative number as string)
     * @param chatTitle title of the Telegram group / channel
     * @return {@code true} if the code was valid and the global address was saved
     */
    public boolean complete(String configId, String code, String chatId, String chatTitle) {
        log.debug("ENTER complete: [configId={}, code={}, chatId={}]", configId, code, chatId);

        PendingGroupRegistration pending = byCode.get(code);
        if (pending == null) {
            log.warn("Group registration code not found [code={}]", code);
            return false;
        }
        if (isExpired(pending)) {
            byCode.remove(code);
            byId.remove(pending.registrationId());
            log.warn("Group registration expired [code={}, regId={}]", code, pending.registrationId());
            return false;
        }
        if (!pending.providerConfigId().equals(configId)) {
            log.warn("Group registration config mismatch [expected={}, got={}]",
                    pending.providerConfigId(), configId);
            return false;
        }

        String label = (chatTitle != null && !chatTitle.isBlank()) ? chatTitle : "Telegram Group";
        GlobalAddress address = new GlobalAddress(
                UUID.randomUUID().toString(),
                pending.providerConfigId(),
                label,
                NotificationMediaType.TELEGRAM,
                chatId,
                System.currentTimeMillis());
        globalAddressRepo.save(address);

        PendingGroupRegistration completed = new PendingGroupRegistration(
                pending.registrationId(), pending.providerConfigId(),
                pending.code(), pending.startedAt(), address);
        byId.put(pending.registrationId(), completed);
        byCode.remove(code);

        log.info("Group registration completed [configId={}, chatId={}, label={}]",
                configId, chatId, label);
        return true;
    }

    /**
     * Checks the status of a pending group registration.
     *
     * @param registrationId the ID returned by {@link #startGroupRegistration}
     * @return a {@link GroupRegistrationStatus} record; {@code null} if the ID is unknown
     */
    public GroupRegistrationStatus checkStatus(String registrationId) {
        PendingGroupRegistration pending = byId.get(registrationId);
        if (pending == null) return null;
        if (isExpired(pending)) {
            byId.remove(registrationId);
            byCode.remove(pending.code());
            return new GroupRegistrationStatus("expired", null);
        }
        if (pending.completedAddress() != null) {
            return new GroupRegistrationStatus("completed", pending.completedAddress());
        }
        return new GroupRegistrationStatus("pending", null);
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private boolean isExpired(PendingGroupRegistration p) {
        return p.startedAt().plus(EXPIRY).isBefore(Instant.now());
    }

    private void purgeExpired() {
        byId.values().removeIf(p -> {
            if (isExpired(p)) {
                byCode.remove(p.code());
                return true;
            }
            return false;
        });
    }

    // ── Value types ───────────────────────────────────────────────────────────

    /**
     * Returned by {@link #startGroupRegistration} to give the UI what it needs.
     *
     * @param registrationId opaque ID used for status polling
     * @param code           one-time code the admin must type in the group
     */
    public record GroupRegistrationInfo(String registrationId, String code) {}

    /**
     * Returned by {@link #checkStatus}.
     *
     * @param status  {@code "pending"}, {@code "completed"}, or {@code "expired"}
     * @param address the saved {@link GlobalAddress} when status is {@code "completed"};
     *                {@code null} otherwise
     */
    public record GroupRegistrationStatus(String status, GlobalAddress address) {}

    /** Internal state for one pending group registration. */
    private record PendingGroupRegistration(
            String          registrationId,
            String          providerConfigId,
            String          code,
            Instant         startedAt,
            GlobalAddress   completedAddress) {}
}
