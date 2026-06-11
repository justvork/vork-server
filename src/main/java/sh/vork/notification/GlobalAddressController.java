package sh.vork.notification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;

import sh.vork.notification.slack.SlackChannelRegistrationService;
import sh.vork.notification.telegram.TelegramGroupRegistrationService;

/**
 * REST controller for managing {@link GlobalAddress} entries within a
 * {@link NotificationProviderConfig}, plus the Telegram group registration flow.
 *
 * <h3>REST API</h3>
 * <ul>
 *   <li>{@code GET    /api/notification-providers/{configId}/global-addresses}
 *       — list all global addresses for a provider config</li>
 *   <li>{@code POST   /api/notification-providers/{configId}/global-addresses}
 *       — add a global address (form/email/SMS providers)</li>
 *   <li>{@code DELETE /api/notification-providers/{configId}/global-addresses/{uuid}}
 *       — remove a global address</li>
 *   <li>{@code GET    /api/notification-providers/{configId}/global-addresses/instructions}
 *       — return provider-specific setup instructions</li>
 *   <li>{@code POST   /api/notification/telegram/{configId}/group-registration/start}
 *       — start Telegram group registration; returns a one-time code</li>
 *   <li>{@code GET    /api/notification/telegram/{configId}/group-registration/{regId}/status}
 *       — poll registration status</li>
 * </ul>
 *
 * <p>All endpoints require the {@code ROLE_ADMIN} authority.
 */
@RestController
@RequestMapping
@PreAuthorize("hasRole('ADMIN')")
public class GlobalAddressController {

    private static final Logger log = LoggerFactory.getLogger(GlobalAddressController.class);

    private final DatabaseRepository<GlobalAddress>              globalAddressRepo;
    private final DatabaseRepository<NotificationProviderConfig> configRepo;
    private final ApplicationContext                             appContext;
    private final TelegramGroupRegistrationService               telegramGroupRegService;
    private final SlackChannelRegistrationService                slackChannelRegService;

    public GlobalAddressController(
            DatabaseRepository<GlobalAddress> globalAddressRepo,
            DatabaseRepository<NotificationProviderConfig> configRepo,
            ApplicationContext appContext,
            TelegramGroupRegistrationService telegramGroupRegService,
            SlackChannelRegistrationService slackChannelRegService) {
        this.globalAddressRepo     = globalAddressRepo;
        this.configRepo            = configRepo;
        this.appContext             = appContext;
        this.telegramGroupRegService = telegramGroupRegService;
        this.slackChannelRegService  = slackChannelRegService;
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @GetMapping("/api/notification-providers/{configId}/global-addresses")
    public ResponseEntity<?> listGlobalAddresses(@PathVariable String configId) {
        log.debug("ENTER listGlobalAddresses: [configId={}]", configId);

        NotificationProviderConfig config = configRepo.get(configId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }

        List<GlobalAddress> addresses;
        try (var stream = globalAddressRepo.search(
                0, Integer.MAX_VALUE, "label", SortOrder.ASC,
                SearchQuery.eq("providerConfigId", configId))) {
            addresses = stream.collect(Collectors.toList());
        }

        log.debug("EXIT listGlobalAddresses: [count={}]", addresses.size());
        return ResponseEntity.ok(addresses);
    }

    // ── Instructions ──────────────────────────────────────────────────────────

    @GetMapping("/api/notification-providers/{configId}/global-addresses/instructions")
    public ResponseEntity<?> getSetupInstructions(@PathVariable String configId) {
        log.debug("ENTER getSetupInstructions: [configId={}]", configId);

        NotificationProviderConfig config = configRepo.get(configId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }

        NotificationProvider provider = resolveProvider(config.providerKey());
        if (provider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Provider not found: " + config.providerKey()));
        }

        return ResponseEntity.ok(Map.of(
                "providerKey",   config.providerKey(),
                "displayName",   provider.getDisplayName(),
                "instructions",  provider.getGlobalAddressSetupInstructions(),
                "mediaTypes",    provider.getSupportedMediaTypes(),
                "supportsGlobal", provider.supportsGlobalAddresses()
        ));
    }

    // ── Add (non-Telegram) ────────────────────────────────────────────────────

    @PostMapping("/api/notification-providers/{configId}/global-addresses")
    public ResponseEntity<?> addGlobalAddress(@PathVariable String configId,
                                               @RequestBody AddGlobalAddressRequest req) {
        log.debug("ENTER addGlobalAddress: [configId={}, mediaType={}, label={}]",
                configId, req.mediaType(), req.label());

        NotificationProviderConfig config = configRepo.get(configId);
        if (config == null) {
            return ResponseEntity.notFound().build();
        }

        NotificationProvider provider = resolveProvider(config.providerKey());
        if (provider == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Provider not found: " + config.providerKey()));
        }

        // Validate the label
        if (req.label() == null || req.label().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Label is required."));
        }

        // Validate the address via the provider
        String addressError = provider.validateAddress(req.mediaType(), req.address());
        if (addressError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", addressError));
        }

        GlobalAddress address = new GlobalAddress(
                UUID.randomUUID().toString(),
                configId,
                req.label().trim(),
                req.mediaType(),
                req.address().trim(),
                System.currentTimeMillis());
        globalAddressRepo.save(address);

        log.info("Global address added [configId={}, label={}, mediaType={}]",
                configId, address.label(), address.mediaType());
        return ResponseEntity.ok(address);
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    @DeleteMapping("/api/notification-providers/{configId}/global-addresses/{uuid}")
    public ResponseEntity<?> deleteGlobalAddress(@PathVariable String configId,
                                                   @PathVariable String uuid) {
        log.debug("ENTER deleteGlobalAddress: [configId={}, uuid={}]", configId, uuid);

        GlobalAddress existing = globalAddressRepo.get(uuid);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (!configId.equals(existing.providerConfigId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Address does not belong to this provider."));
        }

        globalAddressRepo.delete(uuid);
        log.info("Global address deleted [uuid={}, label={}]", uuid, existing.label());
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // ── Telegram group registration ───────────────────────────────────────────

    @PostMapping("/api/notification/telegram/{configId}/group-registration/start")
    public ResponseEntity<?> startGroupRegistration(@PathVariable String configId) {
        log.debug("ENTER startGroupRegistration: [configId={}]", configId);

        try {
            TelegramGroupRegistrationService.GroupRegistrationInfo info =
                    telegramGroupRegService.startGroupRegistration(configId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("registrationId", info.registrationId());
            response.put("code",           info.code());
            response.put("instructions",
                    "Add the bot to your Telegram group, then type inside the group:\n"
                    + "/register " + info.code());

            log.info("Group registration started [configId={}, regId={}]",
                    configId, info.registrationId());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/notification/telegram/{configId}/group-registration/{regId}/status")
    public ResponseEntity<?> checkGroupRegistrationStatus(@PathVariable String configId,
                                                            @PathVariable String regId) {
        log.debug("ENTER checkGroupRegistrationStatus: [configId={}, regId={}]", configId, regId);

        TelegramGroupRegistrationService.GroupRegistrationStatus status =
                telegramGroupRegService.checkStatus(regId);

        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }

    // ── Slack channel registration ────────────────────────────────────────────

    @PostMapping("/api/notification/slack/{configId}/channel-registration/start")
    public ResponseEntity<?> startSlackChannelRegistration(@PathVariable String configId) {
        log.debug("ENTER startSlackChannelRegistration: [configId={}]", configId);
        try {
            SlackChannelRegistrationService.ChannelRegistrationInfo info =
                    slackChannelRegService.startChannelRegistration(configId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("registrationId", info.registrationId());
            response.put("code",           info.code());
            response.put("instructions",
                    "Add the bot to your Slack channel, then type inside the channel:\n"
                    + "register " + info.code());

            log.info("Slack channel registration started [configId={}, regId={}]",
                    configId, info.registrationId());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/notification/slack/{configId}/channel-registration/{regId}/status")
    public ResponseEntity<?> checkSlackChannelRegistrationStatus(
            @PathVariable String configId, @PathVariable String regId) {
        log.debug("ENTER checkSlackChannelRegistrationStatus: [configId={}, regId={}]",
                configId, regId);
        SlackChannelRegistrationService.ChannelRegistrationStatus status =
                slackChannelRegService.checkStatus(regId);
        if (status == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(status);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private NotificationProvider resolveProvider(String providerKey) {
        return appContext.getBeansOfType(NotificationProvider.class)
                .values().stream()
                .filter(p -> p.getProviderKey().equals(providerKey))
                .findFirst()
                .orElse(null);
    }

    // ── Request DTO ───────────────────────────────────────────────────────────

    record AddGlobalAddressRequest(
            String               label,
            NotificationMediaType mediaType,
            String               address
    ) {}
}
