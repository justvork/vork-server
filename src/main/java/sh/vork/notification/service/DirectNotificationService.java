package sh.vork.notification.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;

import sh.vork.notification.Notification;
import sh.vork.notification.NotificationDeliveryState;
import sh.vork.notification.NotificationLedgerEntry;
import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProvider;
import sh.vork.notification.NotificationProviderAttempt;
import sh.vork.notification.NotificationProviderConfig;

/**
 * Provides direct (unregistered-address) notification delivery for the AI tool layer.
 *
 * <p>Unlike user-scoped delivery (which routes through {@link sh.vork.notification.user.UserNotificationMedia}),
 * this service accepts an arbitrary address and a specific {@link NotificationProviderConfig} UUID
 * chosen by the caller.  Providers that do not support direct addressing (e.g. Telegram) are
 * excluded from discovery and cannot be used.
 */
@Service
public class DirectNotificationService {

    private static final Logger log = LoggerFactory.getLogger(DirectNotificationService.class);

        public record SendResult(
            String status,
            String message,
            String ledgerEntryId,
            String idempotencyKey
        ) {}

    /** Inner record returned by {@link #listAvailable()} — safe to serialise to JSON. */
    public record ProviderSummary(
            String configId,
            String displayName,
            String providerKey,
            Set<NotificationMediaType> mediaTypes
    ) {}

    private final DatabaseRepository<NotificationProviderConfig> providerConfigRepo;
        private final DatabaseRepository<NotificationLedgerEntry> notificationLedgerRepo;
    private final ApplicationContext applicationContext;

    public DirectNotificationService(
            DatabaseRepository<NotificationProviderConfig> providerConfigRepo,
            DatabaseRepository<NotificationLedgerEntry> notificationLedgerRepo,
            ApplicationContext applicationContext) {
        this.providerConfigRepo = providerConfigRepo;
        this.notificationLedgerRepo = notificationLedgerRepo;
        this.applicationContext = applicationContext;
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    /**
     * Returns all configured notification providers that support direct
     * (unregistered) addressing.
     *
     * <p>A provider appears in this list only when:
     * <ol>
     *   <li>It implements {@link NotificationProvider#supportsDirectAddress()} returning
     *       {@code true}; and</li>
     *   <li>At least one {@link NotificationProviderConfig} with a matching
     *       {@code providerKey} exists in MongoDB.</li>
     * </ol>
     */
    public List<ProviderSummary> listAvailable() {
        log.debug("ENTER listAvailable");

        Map<String, NotificationProvider> providerBeans =
                applicationContext.getBeansOfType(NotificationProvider.class)
                        .values().stream()
                        .filter(NotificationProvider::supportsDirectAddress)
                        .collect(Collectors.toMap(NotificationProvider::getProviderKey, p -> p, (a, b) -> a));

        if (providerBeans.isEmpty()) {
            log.debug("EXIT listAvailable: no direct-address providers registered");
            return List.of();
        }

        List<ProviderSummary> result = new ArrayList<>();
        try (var stream = providerConfigRepo.list(0, Integer.MAX_VALUE)) {
            stream.forEach(cfg -> {
                NotificationProvider provider = providerBeans.get(cfg.providerKey());
                if (provider != null) {
                    result.add(new ProviderSummary(
                            cfg.uuid(),
                            cfg.displayName(),
                            cfg.providerKey(),
                            provider.getSupportedMediaTypes()
                    ));
                }
            });
        }

        log.debug("EXIT listAvailable: found {} provider config(s)", result.size());
        return result;
    }

    // ── Delivery ──────────────────────────────────────────────────────────────

    /**
     * Sends a notification to {@code address} using the provider config identified
     * by {@code providerConfigId}.
     *
     * @param providerConfigId UUID of the {@link NotificationProviderConfig} to use
     * @param title            subject / headline
     * @param body             plain-text body
     * @param address          delivery address (email or E.164 phone number)
     * @return {@code "ok"} on success, or a human-readable error string on failure
     */
    public SendResult send(String providerConfigId, String title, String body, String address) {
        return send(providerConfigId, title, body, Notification.CONTENT_TYPE_TEXT, null, null, null, address);
    }

    /**
     * Sends a notification to {@code address} using the provider config identified
     * by {@code providerConfigId} and explicit body content type.
     */
    public SendResult send(String providerConfigId,
                           String title,
                           String body,
                           String bodyContentType,
                           String address) {
        return send(providerConfigId, title, body, bodyContentType, null, null, null, address);
    }

    public SendResult send(String providerConfigId,
                           String title,
                           String body,
                           String bodyContentType,
                           String idempotencyGroup,
                           String originatingAgent,
                           String originatingSkill,
                           String address) {
        log.debug("ENTER send: providerConfigId={}, address={}", providerConfigId, address);

        long now = System.currentTimeMillis();
        String createdBy = resolveUsername();
        String originatingSessionUuid = resolveSessionUuid();

        NotificationProviderConfig cfg = providerConfigRepo.get(providerConfigId);
        if (cfg == null) {
            log.warn("send: provider config not found [configId={}]", providerConfigId);
            NotificationLedgerEntry entry = createLedgerEntry(
                    now,
                    null,
                    normalizeAddress(address),
                    title,
                    body,
                    normalizeIdempotencyGroup(idempotencyGroup),
                    null,
                    originatingAgent,
                    originatingSessionUuid,
                    originatingSkill,
                    createdBy,
                    providerConfigId,
                    null,
                    null,
                    NotificationDeliveryState.FAILED,
                    "provider config '" + providerConfigId + "' not found");
            return new SendResult("error", entry.errorMessage(), entry.uuid(), entry.idempotencyKey());
        }

        Map<String, NotificationProvider> providerBeans =
                applicationContext.getBeansOfType(NotificationProvider.class)
                        .values().stream()
                        .collect(Collectors.toMap(NotificationProvider::getProviderKey, p -> p, (a, b) -> a));

        NotificationProvider provider = providerBeans.get(cfg.providerKey());
        if (provider == null) {
            log.warn("send: no provider bean for key '{}' [configId={}]", cfg.providerKey(), providerConfigId);
            NotificationLedgerEntry entry = createLedgerEntry(
                    now,
                    firstMediaType(provider, cfg),
                    normalizeAddress(address),
                    title,
                    body,
                    normalizeIdempotencyGroup(idempotencyGroup),
                    null,
                    originatingAgent,
                    originatingSessionUuid,
                    originatingSkill,
                    createdBy,
                    providerConfigId,
                    cfg.providerKey(),
                    cfg.displayName(),
                    NotificationDeliveryState.FAILED,
                    "no provider registered for key '" + cfg.providerKey() + "'");
            return new SendResult("error", entry.errorMessage(), entry.uuid(), entry.idempotencyKey());
        }

        NotificationMediaType mediaType = firstMediaType(provider, cfg);
        String normalizedAddress = normalizeAddress(address);
        String normalizedIdempotencyGroup = normalizeIdempotencyGroup(idempotencyGroup);
        String idempotencyKey = buildIdempotencyKey(normalizedIdempotencyGroup, mediaType, normalizedAddress);

        if (idempotencyKey != null && hasSuccessfulLedgerEntry(idempotencyKey)) {
            NotificationLedgerEntry alreadySentEntry = createLedgerEntry(
                    now,
                    mediaType,
                    normalizedAddress,
                    title,
                    body,
                    normalizedIdempotencyGroup,
                    idempotencyKey,
                    originatingAgent,
                    originatingSessionUuid,
                    originatingSkill,
                    createdBy,
                    providerConfigId,
                    cfg.providerKey(),
                    cfg.displayName(),
                    NotificationDeliveryState.ALREADY_SENT,
                    null);
            log.info("Direct notification skipped as already sent [idempotencyKey={}, address={}]",
                    idempotencyKey, normalizedAddress);
            return new SendResult("already sent", "already sent", alreadySentEntry.uuid(), idempotencyKey);
        }

        if (!provider.supportsDirectAddress()) {
            log.warn("send: provider '{}' does not support direct addressing", cfg.providerKey());
            NotificationLedgerEntry entry = createLedgerEntry(
                    now,
                    mediaType,
                    normalizedAddress,
                    title,
                    body,
                    normalizedIdempotencyGroup,
                    idempotencyKey,
                    originatingAgent,
                    originatingSessionUuid,
                    originatingSkill,
                    createdBy,
                    providerConfigId,
                    cfg.providerKey(),
                    cfg.displayName(),
                    NotificationDeliveryState.FAILED,
                    "provider '" + cfg.displayName() + "' requires prior opt-in and cannot send to unregistered addresses");
            return new SendResult("error", entry.errorMessage(), entry.uuid(), entry.idempotencyKey());
        }

        try {
            Notification notification = Notification.of(List.of(address), title, body, bodyContentType);
            provider.send(notification, cfg.settings());
            NotificationLedgerEntry entry = createLedgerEntry(
                    now,
                    mediaType,
                    normalizedAddress,
                    title,
                    body,
                    normalizedIdempotencyGroup,
                    idempotencyKey,
                    originatingAgent,
                    originatingSessionUuid,
                    originatingSkill,
                    createdBy,
                    providerConfigId,
                    cfg.providerKey(),
                    cfg.displayName(),
                    NotificationDeliveryState.SENT,
                    null);
            log.info("Direct notification sent via '{}' to '{}' [configId={}]",
                    cfg.providerKey(), address, providerConfigId);
            return new SendResult("ok", "ok", entry.uuid(), entry.idempotencyKey());
        } catch (Exception e) {
            log.warn("Direct notification delivery failed via '{}' [address={}, error={}]",
                    cfg.providerKey(), address, e.getMessage());
            NotificationLedgerEntry entry = createLedgerEntry(
                    now,
                    mediaType,
                    normalizedAddress,
                    title,
                    body,
                    normalizedIdempotencyGroup,
                    idempotencyKey,
                    originatingAgent,
                    originatingSessionUuid,
                    originatingSkill,
                    createdBy,
                    providerConfigId,
                    cfg.providerKey(),
                    cfg.displayName(),
                    NotificationDeliveryState.FAILED,
                    "delivery failed — " + e.getMessage());
            return new SendResult("error", entry.errorMessage(), entry.uuid(), entry.idempotencyKey());
        }
    }

    private NotificationLedgerEntry createLedgerEntry(long createdAt,
                                                      NotificationMediaType mediaType,
                                                      String destination,
                                                      String title,
                                                      String body,
                                                      String idempotencyGroup,
                                                      String idempotencyKey,
                                                      String originatingAgent,
                                                      String originatingSessionUuid,
                                                      String originatingSkill,
                                                      String createdBy,
                                                      String providerConfigId,
                                                      String providerKey,
                                                      String providerDisplayName,
                                                      NotificationDeliveryState finalState,
                                                      String errorMessage) {
        String uuid = UUID.randomUUID().toString();
        String logicalNotificationId = UUID.randomUUID().toString();
        NotificationProviderAttempt attempt = new NotificationProviderAttempt(
                1,
                createdAt,
                finalState,
                providerConfigId,
                providerKey,
                null,
                errorMessage);
        NotificationLedgerEntry entry = new NotificationLedgerEntry(
                uuid,
                logicalNotificationId,
                idempotencyGroup,
                idempotencyKey,
                mediaType == null ? "unknown" : mediaType.name(),
                destination,
                title == null ? "" : title,
                sha256Hex(body == null ? "" : body),
                normalizeOptional(originatingAgent),
                normalizeOptional(originatingSessionUuid),
                normalizeOptional(originatingSkill),
                normalizeOptional(createdBy),
                createdAt,
                finalState,
                providerConfigId,
                providerKey,
                null,
                errorMessage,
                List.of(attempt));
        notificationLedgerRepo.save(entry);
        log.debug("Step ledgerEntrySaved: [ledgerId={}, state={}, idempotencyKey={}, providerConfigId={}, providerKey={}, providerDisplayName={}]",
                entry.uuid(), entry.finalState(), entry.idempotencyKey(), providerConfigId, providerKey, providerDisplayName);
        return entry;
    }

    private boolean hasSuccessfulLedgerEntry(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return false;
        }
        try (var stream = notificationLedgerRepo.search(
                0,
                1,
                "createdAt",
                SortOrder.DESC,
                SearchQuery.eq("idempotencyKey", idempotencyKey),
                SearchQuery.eq("finalState", NotificationDeliveryState.SENT.name()))) {
            return stream.findFirst().isPresent();
        }
    }

    private static String normalizeIdempotencyGroup(String idempotencyGroup) {
        if (idempotencyGroup == null) {
            return null;
        }
        String normalized = idempotencyGroup.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private static String buildIdempotencyKey(String idempotencyGroup,
                                              NotificationMediaType mediaType,
                                              String address) {
        if (idempotencyGroup == null || idempotencyGroup.isBlank()) {
            return null;
        }
        String media = mediaType == null
                ? "unknown"
                : mediaType.name().toLowerCase(Locale.ROOT);
        String destination = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        return idempotencyGroup + ":" + media + ":" + destination;
    }

    private static NotificationMediaType firstMediaType(NotificationProvider provider,
                                                        NotificationProviderConfig cfg) {
        if (provider == null || provider.getSupportedMediaTypes() == null || provider.getSupportedMediaTypes().isEmpty()) {
            log.debug("firstMediaType unresolved [providerKey={}]", cfg == null ? null : cfg.providerKey());
            return null;
        }
        return provider.getSupportedMediaTypes().iterator().next();
    }

    private static String normalizeAddress(String address) {
        return address == null ? "" : address.trim();
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static String resolveSessionUuid() {
        String sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            return null;
        }
        return sessionUuid;
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(auth.getName())) {
            return null;
        }
        return auth.getName();
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    /**
     * Formats a pending notification for display in an authorization prompt by
     * delegating to the provider identified by {@code providerConfigId}.
     *
     * <p>Falls back to an email-style preview if the provider config or provider
     * bean cannot be resolved.
     *
     * @param providerConfigId UUID of the {@link NotificationProviderConfig} to use
     * @param address          the destination address
     * @param title            the notification subject / headline
     * @param body             the plain-text body
     * @return a human-readable preview string (no markdown fences)
     */
    public String formatDirectNotification(String providerConfigId, String address, String title, String body) {
        log.debug("ENTER formatDirectNotification: providerConfigId={}", providerConfigId);
        try {
            NotificationProviderConfig cfg = providerConfigRepo.get(providerConfigId);
            if (cfg != null) {
                NotificationProvider provider = applicationContext
                        .getBeansOfType(NotificationProvider.class)
                        .values().stream()
                        .filter(p -> cfg.providerKey().equals(p.getProviderKey()))
                        .findFirst()
                        .orElse(null);
                if (provider != null) {
                    return provider.formatDirectNotification(address, title, body);
                }
            }
        } catch (Exception ex) {
            log.debug("formatDirectNotification: provider lookup failed, using default [error={}]", ex.getMessage());
        }
        // Default email-style fallback
        return "To: " + address + "\nSubject: " + title + "\n\n" + body;
    }
}
