package sh.vork.notification.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import sh.vork.orm.mock.MapDatabaseRepository;

import sh.vork.notification.Notification;
import sh.vork.notification.NotificationDeliveryState;
import sh.vork.notification.NotificationLedgerEntry;
import sh.vork.notification.NotificationException;
import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProvider;
import sh.vork.notification.NotificationProviderConfig;
import sh.vork.notification.service.DirectNotificationService.ProviderSummary;

class DirectNotificationServiceTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static NotificationProvider emailProvider(String key, boolean direct) {
        NotificationProvider p = mock(NotificationProvider.class);
        when(p.getProviderKey()).thenReturn(key);
        when(p.getSupportedMediaTypes()).thenReturn(Set.of(NotificationMediaType.EMAIL_ADDRESS));
        when(p.supportsDirectAddress()).thenReturn(direct);
        return p;
    }

    private static NotificationProvider smsProvider(String key) {
        NotificationProvider p = mock(NotificationProvider.class);
        when(p.getProviderKey()).thenReturn(key);
        when(p.getSupportedMediaTypes()).thenReturn(Set.of(NotificationMediaType.PHONE_NUMBER));
        when(p.supportsDirectAddress()).thenReturn(true);
        return p;
    }

    private static NotificationProvider telegramProvider() {
        NotificationProvider p = mock(NotificationProvider.class);
        when(p.getProviderKey()).thenReturn("telegram");
        when(p.getSupportedMediaTypes()).thenReturn(Set.of(NotificationMediaType.TELEGRAM));
        when(p.supportsDirectAddress()).thenReturn(false);
        return p;
    }

    private static NotificationProviderConfig config(String uuid, String providerKey, String displayName) {
        return new NotificationProviderConfig(uuid, providerKey, displayName, Map.of("key", "value"));
    }

    // ── Discovery tests ───────────────────────────────────────────────────────

    @Nested
    class ListAvailableTests {

        @Test
        void returnsConfiguredDirectAddressProviders() {
            var sendgrid = emailProvider("sendgrid", true);
            var twilio   = smsProvider("twilio-sms");

            var repo = new MapDatabaseRepository<>(NotificationProviderConfig.class);
            var ledgerRepo = new MapDatabaseRepository<>(NotificationLedgerEntry.class);
            String sgId  = UUID.randomUUID().toString();
            String twId  = UUID.randomUUID().toString();
            repo.save(config(sgId, "sendgrid", "SendGrid Email"));
            repo.save(config(twId, "twilio-sms", "Twilio SMS"));

            ApplicationContext ctx = mock(ApplicationContext.class);
            when(ctx.getBeansOfType(NotificationProvider.class))
                    .thenReturn(Map.of("sendgrid", sendgrid, "twilio-sms", twilio));

            var service = new DirectNotificationService(repo, ledgerRepo, ctx);
            List<ProviderSummary> result = service.listAvailable();

            assertEquals(2, result.size());
            assertTrue(result.stream().anyMatch(s -> s.configId().equals(sgId)
                    && s.mediaTypes().contains(NotificationMediaType.EMAIL_ADDRESS)));
            assertTrue(result.stream().anyMatch(s -> s.configId().equals(twId)
                    && s.mediaTypes().contains(NotificationMediaType.PHONE_NUMBER)));
        }

        @Test
        void excludesTelegramProvider() {
            var telegram = telegramProvider();

            var repo = new MapDatabaseRepository<>(NotificationProviderConfig.class);
            var ledgerRepo = new MapDatabaseRepository<>(NotificationLedgerEntry.class);
            repo.save(config(UUID.randomUUID().toString(), "telegram", "Telegram"));

            ApplicationContext ctx = mock(ApplicationContext.class);
            when(ctx.getBeansOfType(NotificationProvider.class))
                    .thenReturn(Map.of("telegram", telegram));

            var service = new DirectNotificationService(repo, ledgerRepo, ctx);
            List<ProviderSummary> result = service.listAvailable();

            assertTrue(result.isEmpty(), "Telegram should be excluded from direct-address list");
        }

        @Test
        void excludesProviderWithNoSavedConfig() {
            var sendgrid = emailProvider("sendgrid", true);
            // No config saved for sendgrid

            var repo = new MapDatabaseRepository<>(NotificationProviderConfig.class);
            var ledgerRepo = new MapDatabaseRepository<>(NotificationLedgerEntry.class);

            ApplicationContext ctx = mock(ApplicationContext.class);
            when(ctx.getBeansOfType(NotificationProvider.class))
                    .thenReturn(Map.of("sendgrid", sendgrid));

                var service = new DirectNotificationService(repo, ledgerRepo, ctx);
            assertTrue(service.listAvailable().isEmpty(),
                    "Provider without saved config should not appear in list");
        }

        @Test
        void returnsEmptyWhenNoProvidersRegistered() {
            var repo = new MapDatabaseRepository<>(NotificationProviderConfig.class);
            var ledgerRepo = new MapDatabaseRepository<>(NotificationLedgerEntry.class);
            ApplicationContext ctx = mock(ApplicationContext.class);
            when(ctx.getBeansOfType(NotificationProvider.class)).thenReturn(Map.of());

            var service = new DirectNotificationService(repo, ledgerRepo, ctx);
            assertTrue(service.listAvailable().isEmpty());
        }
    }

    // ── Delivery tests ────────────────────────────────────────────────────────

    @Nested
    class SendTests {

        private MapDatabaseRepository<NotificationProviderConfig> repo;
        private MapDatabaseRepository<NotificationLedgerEntry> ledgerRepo;
        private NotificationProvider sendgrid;
        private NotificationProvider twilio;
        private ApplicationContext ctx;
        private String sgConfigId;

        @BeforeEach
        void setUp() {
            sendgrid = emailProvider("sendgrid", true);
            twilio   = smsProvider("twilio-sms");
            repo     = new MapDatabaseRepository<>(NotificationProviderConfig.class);
            ledgerRepo = new MapDatabaseRepository<>(NotificationLedgerEntry.class);

            sgConfigId = UUID.randomUUID().toString();
            repo.save(config(sgConfigId, "sendgrid", "SendGrid Email"));

            ctx = mock(ApplicationContext.class);
            when(ctx.getBeansOfType(NotificationProvider.class))
                    .thenReturn(Map.of("sendgrid", sendgrid, "twilio-sms", twilio));
        }

        @Test
        void sendsViaCorrectProvider() throws Exception {
            var service = new DirectNotificationService(repo, ledgerRepo, ctx);
            var result = service.send(sgConfigId, "Hello", "World", "user@example.com");

            assertEquals("ok", result.status());
            verify(sendgrid).send(any(Notification.class), eq(Map.of("key", "value")));
            verify(twilio, never()).send(any(), any());
        }

        @Test
        void passesCorrectRecipientAndContent() throws Exception {
            var service = new DirectNotificationService(repo, ledgerRepo, ctx);
            service.send(sgConfigId, "My Title", "My Body", "target@test.com");

            var captor = org.mockito.ArgumentCaptor.forClass(Notification.class);
            verify(sendgrid).send(captor.capture(), any());

            Notification sent = captor.getValue();
            assertEquals(List.of("target@test.com"), sent.recipients());
            assertEquals("My Title", sent.title());
            assertEquals("My Body", sent.body());
        }

        @Test
        void passesRequestedHtmlBodyContentType() throws Exception {
            var service = new DirectNotificationService(repo, ledgerRepo, ctx);
            service.send(sgConfigId, "My Title", "<h1>My Body</h1>", Notification.CONTENT_TYPE_HTML, "target@test.com");

            var captor = org.mockito.ArgumentCaptor.forClass(Notification.class);
            verify(sendgrid).send(captor.capture(), any());

            Notification sent = captor.getValue();
            assertEquals(Notification.CONTENT_TYPE_HTML, sent.bodyContentType());
        }

        @Test
        void returnsErrorForUnknownConfigId() throws Exception {
            var service = new DirectNotificationService(repo, ledgerRepo, ctx);
            var result = service.send("non-existent-uuid", "Hi", "Body", "x@y.com");

            assertEquals("error", result.status());
            verify(sendgrid, never()).send(any(), any());
        }

        @Test
        void returnsErrorWhenProviderThrows() throws Exception {
            org.mockito.Mockito.doThrow(new NotificationException("API down")).when(sendgrid).send(any(), any());

            var service = new DirectNotificationService(repo, ledgerRepo, ctx);
            var result = service.send(sgConfigId, "Hi", "Body", "x@y.com");

            assertEquals("error", result.status());
        }

        @Test
        void returnsErrorWhenProviderBeanMissing() throws Exception {
            // Config exists for a providerKey that has no registered Spring bean
            String orphanId = UUID.randomUUID().toString();
            repo.save(config(orphanId, "unknown-provider", "Ghost"));

            var service = new DirectNotificationService(repo, ledgerRepo, ctx);
            var result = service.send(orphanId, "Hi", "Body", "x@y.com");

            assertEquals("error", result.status(), "Expected error for missing provider bean");
        }

        @Test
        void returnsErrorIfProviderDoesNotSupportDirectAddress() throws Exception {
            var telegram = telegramProvider();
            String tgId = UUID.randomUUID().toString();
            repo.save(config(tgId, "telegram", "Telegram"));

            when(ctx.getBeansOfType(NotificationProvider.class))
                    .thenReturn(Map.of("sendgrid", sendgrid, "telegram", telegram));

            var service = new DirectNotificationService(repo, ledgerRepo, ctx);
            var result = service.send(tgId, "Hi", "Body", "@someuser");

            assertEquals("error", result.status(), "Expected error for non-direct provider");
        }

        @Test
        void suppressesDuplicateSuccessfulSendWhenIdempotencyGroupProvided() throws Exception {
            var service = new DirectNotificationService(repo, ledgerRepo, ctx);

            var first = service.send(
                    sgConfigId,
                    "Hello",
                    "World",
                    Notification.CONTENT_TYPE_TEXT,
                    "sales-campaign-28-08-2026",
                    "Concierge",
                    "marketing-skill",
                    "user@example.com");

            var second = service.send(
                    sgConfigId,
                    "Hello",
                    "World",
                    Notification.CONTENT_TYPE_TEXT,
                    "sales-campaign-28-08-2026",
                    "Concierge",
                    "marketing-skill",
                    "user@example.com");

            assertEquals("ok", first.status());
            assertEquals("already sent", second.status());
            verify(sendgrid).send(any(Notification.class), eq(Map.of("key", "value")));

            long sentCount = ledgerRepo.searchCount(
                    sh.vork.orm.SearchQuery.eq("finalState", NotificationDeliveryState.SENT.name()));
            long alreadySentCount = ledgerRepo.searchCount(
                    sh.vork.orm.SearchQuery.eq("finalState", NotificationDeliveryState.ALREADY_SENT.name()));
            assertEquals(1L, sentCount);
            assertEquals(1L, alreadySentCount);
        }

        @Test
        void failedSendDoesNotBlockFutureAttemptWithSameIdempotencyKey() throws Exception {
            org.mockito.Mockito.doThrow(new NotificationException("temporary outage"))
                    .doNothing()
                    .when(sendgrid)
                    .send(any(), any());

            var service = new DirectNotificationService(repo, ledgerRepo, ctx);

            var first = service.send(
                    sgConfigId,
                    "Retry",
                    "Body",
                    Notification.CONTENT_TYPE_TEXT,
                    "sales-campaign-28-08-2026",
                    "Concierge",
                    "marketing-skill",
                    "user@example.com");

            var second = service.send(
                    sgConfigId,
                    "Retry",
                    "Body",
                    Notification.CONTENT_TYPE_TEXT,
                    "sales-campaign-28-08-2026",
                    "Concierge",
                    "marketing-skill",
                    "user@example.com");

            assertEquals("error", first.status());
            assertEquals("ok", second.status());

            verify(sendgrid, org.mockito.Mockito.times(2)).send(any(Notification.class), eq(Map.of("key", "value")));

            long failedCount = ledgerRepo.searchCount(
                    sh.vork.orm.SearchQuery.eq("finalState", NotificationDeliveryState.FAILED.name()));
            long sentCount = ledgerRepo.searchCount(
                    sh.vork.orm.SearchQuery.eq("finalState", NotificationDeliveryState.SENT.name()));
            assertEquals(1L, failedCount);
            assertEquals(1L, sentCount);
        }

        @Test
        void writesIdempotencyKeyToLedgerWhenGroupProvided() {
            var service = new DirectNotificationService(repo, ledgerRepo, ctx);

            service.send(
                    sgConfigId,
                    "Hello",
                    "World",
                    Notification.CONTENT_TYPE_TEXT,
                    "sales-campaign-28-08-2026",
                    "Concierge",
                    "marketing-skill",
                    "USER@Example.com");

            try (var stream = ledgerRepo.list(0, 10)) {
                NotificationLedgerEntry entry = stream.findFirst().orElseThrow();
                assertEquals("sales-campaign-28-08-2026:email_address:user@example.com", entry.idempotencyKey());
            }
        }
    }
}
