package sh.vork.ai.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.attention.AttentionAlert;
import sh.vork.attention.AttentionAlertService;
import sh.vork.channel.ChannelRef;
import sh.vork.channel.ChannelService;
import sh.vork.orm.RepositoryFactory;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.scheduling.service.BackgroundNotificationService;
import sh.vork.setup.SystemSettings;
import sh.vork.setup.SystemSettingsService;
import sh.vork.web.RequestOriginContext;

class RequestInformationServiceTest {

    @Test
    void recordResponseAndEvaluate_firstOfMultipleResponses_keepsCampaignOpenWithoutNpe() {
        MapDatabaseRepository<RequestInformationCampaign> campaignRepo =
                new MapDatabaseRepository<>(RequestInformationCampaign.class);
        MapDatabaseRepository<RequestInformationResponse> responseRepo =
                new MapDatabaseRepository<>(RequestInformationResponse.class);
        MapDatabaseRepository<AiSession> sessionRepo =
                new MapDatabaseRepository<>(AiSession.class);

        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.create(RequestInformationCampaign.class)).thenReturn(campaignRepo);
        when(repositoryFactory.create(RequestInformationResponse.class)).thenReturn(responseRepo);
        when(repositoryFactory.create(AiSession.class)).thenReturn(sessionRepo);

        ChannelService channelService = mock(ChannelService.class);
        when(channelService.resolveByChannelName("alice"))
                .thenReturn(Optional.of(new ChannelRef("alice", "Alice", "local")));
        when(channelService.resolveByChannelName("bob"))
                .thenReturn(Optional.of(new ChannelRef("bob", "Bob", "local")));

        AttentionAlertService attentionAlertService = mock(AttentionAlertService.class);
        BackgroundNotificationService backgroundNotificationService = mock(BackgroundNotificationService.class);
        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        when(systemSettingsService.getGlobal())
                .thenReturn(new SystemSettings("global", "GEMINI", "gemini-2.5-flash", null, 15));

        RequestInformationService service = new RequestInformationService(
                repositoryFactory,
                channelService,
                attentionAlertService,
                backgroundNotificationService,
                systemSettingsService,
                new ObjectMapper().findAndRegisterModules(),
                "");

        RequestInformationCampaign campaign = new RequestInformationCampaign(
                "campaign-1",
                "session-1",
                "event-1",
                "admin",
                "Need both responses",
                List.of("alice", "bob"),
                RequestResponsePolicy.ALL,
                2,
                List.of(),
                RequestCampaignStatus.OPEN,
                false,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                null,
                "session-1",
                java.util.Map.of(),
                RequestResponseRouteMode.EXTERNAL_FORM,
                false);
        campaignRepo.save(campaign);

        RequestInformationService.ResponseGateResult result = service.recordResponseAndEvaluate(
                "campaign-1",
                "alice",
                "ONCE",
                java.util.Map.of("answer", "ready"));

        assertTrue(result.accepted());
        assertTrue(!result.shouldResume());
        assertEquals(1, result.responseCount());
        assertEquals(2, result.requiredResponses());

        RequestInformationCampaign saved = service.getCampaign("campaign-1");
        assertEquals(RequestCampaignStatus.OPEN, saved.status());
        assertEquals(List.of("alice"), saved.respondedChannels());
        assertEquals(null, saved.satisfiedAt());
    }

    @Test
        void ensureCampaignForSuspension_whenRequestContextAvailable_usesRequestThreadLocalBaseUrl() throws Exception {
        MapDatabaseRepository<RequestInformationCampaign> campaignRepo =
                new MapDatabaseRepository<>(RequestInformationCampaign.class);
        MapDatabaseRepository<RequestInformationResponse> responseRepo =
                new MapDatabaseRepository<>(RequestInformationResponse.class);
        MapDatabaseRepository<AiSession> sessionRepo =
                new MapDatabaseRepository<>(AiSession.class);

        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.create(RequestInformationCampaign.class)).thenReturn(campaignRepo);
        when(repositoryFactory.create(RequestInformationResponse.class)).thenReturn(responseRepo);
        when(repositoryFactory.create(AiSession.class)).thenReturn(sessionRepo);

        ChannelService channelService = mock(ChannelService.class);
        when(channelService.resolveByChannelName("lee"))
                .thenReturn(Optional.of(new ChannelRef("lee", "Lee", "local")));

        AttentionAlertService attentionAlertService = mock(AttentionAlertService.class);
        when(attentionAlertService.create(any())).thenAnswer(invocation -> {
            AttentionAlertService.CreateAttentionAlertCommand command = invocation.getArgument(0);
            return new AttentionAlert(
                    "alert-1",
                    command.channelNames(),
                    command.alertName(),
                    command.description(),
                    command.resolutionPolicy(),
                    command.actionUrl(),
                    command.attentionAt(),
                    command.sourceType(),
                    command.sourceId(),
                    System.currentTimeMillis(),
                    System.currentTimeMillis());
        });

        BackgroundNotificationService backgroundNotificationService = mock(BackgroundNotificationService.class);

        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        when(systemSettingsService.getGlobal())
                .thenReturn(new SystemSettings("global", "GEMINI", "gemini-2.5-flash", "https://ignored.example", 15));

        RequestInformationService service = new RequestInformationService(
                repositoryFactory,
                channelService,
                attentionAlertService,
                backgroundNotificationService,
                systemSettingsService,
                new ObjectMapper().findAndRegisterModules(),
                "https://relay.vork.sh");

        setChildSessionRoutingEnabled(service, true);

        sessionRepo.save(new AiSession(
                "session-1",
                "GEMINI",
                SessionOriginMode.WEB,
                "admin",
                "Parent Session",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.AWAITING_INPUT,
                null,
                null,
                null,
                null,
                null));

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
        when(request.getHeader("X-Forwarded-Host")).thenReturn("local.vork.dev");
        when(request.getHeader("Host")).thenReturn(null);
        when(request.getHeader("X-Forwarded-Port")).thenReturn("443");
        when(request.getScheme()).thenReturn("http");
        when(request.getServerName()).thenReturn("localhost");
        when(request.getServerPort()).thenReturn(8080);

        try {
            RequestOriginContext.bind(request);
            ToolSuspensionException.SuspensionCampaign requestedCampaign =
                    new ToolSuspensionException.SuspensionCampaign(
                            List.of("lee"),
                            RequestResponsePolicy.FIRST,
                            1,
                            false,
                            "Sales Meeting Time Query",
                            "Requesting the sales meeting time for tomorrow from Lee.",
                            null,
                            0L);

            service.ensureCampaignForSuspension(
                    "session-1",
                    "event-1",
                    "admin",
                    "requestInformation",
                    "What time is the sales meeting tomorrow?",
                    requestedCampaign);
        } finally {
            RequestOriginContext.clear();
        }

        ArgumentCaptor<AttentionAlertService.CreateAttentionAlertCommand> alertCaptor =
                ArgumentCaptor.forClass(AttentionAlertService.CreateAttentionAlertCommand.class);
        verify(attentionAlertService).create(alertCaptor.capture());
        assertTrue(alertCaptor.getValue().actionUrl().startsWith("https://local.vork.dev/chat?sessionUuid="));
    }

    @Test
                void ensureCampaignForSuspension_whenNoBaseUrl_usesRelativeChatLinks() throws Exception {
        MapDatabaseRepository<RequestInformationCampaign> campaignRepo =
                new MapDatabaseRepository<>(RequestInformationCampaign.class);
        MapDatabaseRepository<RequestInformationResponse> responseRepo =
                new MapDatabaseRepository<>(RequestInformationResponse.class);
        MapDatabaseRepository<AiSession> sessionRepo =
                new MapDatabaseRepository<>(AiSession.class);

        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.create(RequestInformationCampaign.class)).thenReturn(campaignRepo);
        when(repositoryFactory.create(RequestInformationResponse.class)).thenReturn(responseRepo);
        when(repositoryFactory.create(AiSession.class)).thenReturn(sessionRepo);

        ChannelService channelService = mock(ChannelService.class);
        when(channelService.resolveByChannelName("lee"))
                .thenReturn(Optional.of(new ChannelRef("lee", "Lee", "local")));

        AttentionAlertService attentionAlertService = mock(AttentionAlertService.class);
        when(attentionAlertService.create(any())).thenAnswer(invocation -> {
            AttentionAlertService.CreateAttentionAlertCommand command = invocation.getArgument(0);
            return new AttentionAlert(
                    "alert-1",
                    command.channelNames(),
                    command.alertName(),
                    command.description(),
                    command.resolutionPolicy(),
                    command.actionUrl(),
                    command.attentionAt(),
                    command.sourceType(),
                    command.sourceId(),
                    System.currentTimeMillis(),
                    System.currentTimeMillis());
        });

        BackgroundNotificationService backgroundNotificationService = mock(BackgroundNotificationService.class);

        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        when(systemSettingsService.getGlobal())
                .thenReturn(new SystemSettings("global", "GEMINI", "gemini-2.5-flash", null, 15));

        RequestInformationService service = new RequestInformationService(
                repositoryFactory,
                channelService,
                attentionAlertService,
                backgroundNotificationService,
                systemSettingsService,
                new ObjectMapper().findAndRegisterModules(),
                "");

        setChildSessionRoutingEnabled(service, true);

        sessionRepo.save(new AiSession(
                "session-1",
                "GEMINI",
                SessionOriginMode.WEB,
                "admin",
                "Parent Session",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.AWAITING_INPUT,
                null,
                null,
                null,
                null,
                null));

        ToolSuspensionException.SuspensionCampaign requestedCampaign =
                new ToolSuspensionException.SuspensionCampaign(
                        List.of("lee"),
                        RequestResponsePolicy.FIRST,
                        1,
                        false,
                        "Sales Meeting Time Query",
                        "Requesting the sales meeting time for tomorrow from Lee.",
                        null,
                        0L);

        String campaignUuid = service.ensureCampaignForSuspension(
                "session-1",
                "event-1",
                "admin",
                "requestInformation",
                "What time is the sales meeting tomorrow?",
                requestedCampaign);

        assertNotNull(campaignUuid);
        RequestInformationCampaign saved = service.getCampaign(campaignUuid);
        assertNotNull(saved);
        assertEquals(RequestCampaignStatus.OPEN, saved.status());
        assertEquals(List.of("lee"), saved.targetChannels());
        assertEquals("session-1", saved.parentSessionUuid());
        assertEquals(RequestResponseRouteMode.CHILD_SESSION, saved.responseRouteMode());
        assertEquals(true, saved.childSessionRoutingEnabled());
        assertTrue(saved.childSessionUuidsByChannel().containsKey("lee"));

        ArgumentCaptor<AttentionAlertService.CreateAttentionAlertCommand> alertCaptor =
                ArgumentCaptor.forClass(AttentionAlertService.CreateAttentionAlertCommand.class);
        verify(attentionAlertService).create(alertCaptor.capture());
        assertTrue(alertCaptor.getValue().actionUrl().startsWith("/chat?sessionUuid="));

        try (var stream = campaignRepo.list(0, 10)) {
            assertTrue(stream.findFirst().isPresent());
        }
    }

    @Test
    void ensureCampaignForSuspension_whenChildRoutingEnabled_registersChildSessionLinks() throws Exception {
        MapDatabaseRepository<RequestInformationCampaign> campaignRepo =
                new MapDatabaseRepository<>(RequestInformationCampaign.class);
        MapDatabaseRepository<RequestInformationResponse> responseRepo =
                new MapDatabaseRepository<>(RequestInformationResponse.class);
        MapDatabaseRepository<AiSession> sessionRepo =
                new MapDatabaseRepository<>(AiSession.class);

        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.create(RequestInformationCampaign.class)).thenReturn(campaignRepo);
        when(repositoryFactory.create(RequestInformationResponse.class)).thenReturn(responseRepo);
        when(repositoryFactory.create(AiSession.class)).thenReturn(sessionRepo);

        ChannelService channelService = mock(ChannelService.class);
        when(channelService.resolveByChannelName("lee"))
                .thenReturn(Optional.of(new ChannelRef("lee", "Lee", "local")));

        AttentionAlertService attentionAlertService = mock(AttentionAlertService.class);
        when(attentionAlertService.create(any())).thenAnswer(invocation -> {
            AttentionAlertService.CreateAttentionAlertCommand command = invocation.getArgument(0);
            return new AttentionAlert(
                    "alert-1",
                    command.channelNames(),
                    command.alertName(),
                    command.description(),
                    command.resolutionPolicy(),
                    command.actionUrl(),
                    command.attentionAt(),
                    command.sourceType(),
                    command.sourceId(),
                    System.currentTimeMillis(),
                    System.currentTimeMillis());
        });

        BackgroundNotificationService backgroundNotificationService = mock(BackgroundNotificationService.class);

        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        when(systemSettingsService.getGlobal())
                .thenReturn(new SystemSettings("global", "GEMINI", "gemini-2.5-flash", null, 15));

        RequestInformationService service = new RequestInformationService(
                repositoryFactory,
                channelService,
                attentionAlertService,
                backgroundNotificationService,
                systemSettingsService,
                new ObjectMapper().findAndRegisterModules(),
                "");

        setChildSessionRoutingEnabled(service, true);

        String parentSessionUuid = "parent-session-1";
        sessionRepo.save(new AiSession(
                parentSessionUuid,
                "GEMINI",
                SessionOriginMode.WEB,
                "admin",
                "Parent Session",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.AWAITING_INPUT,
                null,
                null,
                null,
                null,
                null));

        ToolSuspensionException.SuspensionCampaign requestedCampaign =
                new ToolSuspensionException.SuspensionCampaign(
                        List.of("lee"),
                        RequestResponsePolicy.FIRST,
                        1,
                        false,
                        "Sales Meeting Time Query",
                        "Requesting the sales meeting time for tomorrow from Lee.",
                        null,
                        0L);

        String campaignUuid = service.ensureCampaignForSuspension(
                parentSessionUuid,
                "event-child-route-1",
                "admin",
                "requestInformation",
                "What time is the sales meeting tomorrow?",
                requestedCampaign);

        RequestInformationCampaign saved = service.getCampaign(campaignUuid);
        assertEquals(RequestResponseRouteMode.CHILD_SESSION, saved.responseRouteMode());
        assertTrue(saved.childSessionRoutingEnabled());
        assertEquals(parentSessionUuid, saved.parentSessionUuid());
        assertEquals(1, saved.childSessionUuidsByChannel().size());
        String childSessionUuid = saved.childSessionUuidsByChannel().get("lee");
        assertNotNull(childSessionUuid);

        AiSession childSession = sessionRepo.get(childSessionUuid);
        assertNotNull(childSession);
        assertEquals("lee", childSession.username());
        assertEquals(parentSessionUuid,
                childSession.environmentVariables().get("REQUEST_CAMPAIGN_PARENT_SESSION_UUID"));
        assertEquals(campaignUuid,
                childSession.environmentVariables().get("REQUEST_CAMPAIGN_ID"));
        assertTrue(childSession.messages() != null && childSession.messages().size() >= 2);
        assertEquals("ASSISTANT", childSession.messages().get(0).role());
        assertEquals("Requesting the sales meeting time for tomorrow from Lee.",
                childSession.messages().get(0).content());
        assertEquals("PROMPT_REQUIRED", childSession.messages().get(1).role());

        UiEventFrame childPrompt = new ObjectMapper().readValue(childSession.messages().get(1).content(), UiEventFrame.class);
        assertEquals("PROMPT_REQUIRED", childPrompt.type());
        assertEquals("REQUEST_CAMPAIGN_RESPONSE", childPrompt.intent());
        assertNotNull(childPrompt.formSchema());
        assertEquals("Information Requested", childPrompt.formSchema().title());
        assertEquals("REQUEST_CAMPAIGN_RESPONSE", childPrompt.formSchema().intent());
        assertTrue(childPrompt.formSchema().fields() != null && !childPrompt.formSchema().fields().isEmpty());
        FormField campaignIdField = childPrompt.formSchema().fields().stream()
                .filter(f -> f != null && "requestCampaignId".equals(f.name()))
                .findFirst()
                .orElse(null);
        assertNotNull(campaignIdField);
        assertEquals(campaignUuid, campaignIdField.value());

        ArgumentCaptor<AttentionAlertService.CreateAttentionAlertCommand> alertCaptor =
                ArgumentCaptor.forClass(AttentionAlertService.CreateAttentionAlertCommand.class);
        verify(attentionAlertService).create(alertCaptor.capture());
        assertTrue(alertCaptor.getValue().actionUrl().startsWith("/chat?sessionUuid="));
        assertTrue(alertCaptor.getValue().actionUrl().contains(childSessionUuid));
    }

    @Test
    void ensureCampaignForSuspension_whenChildRoutingEnabled_reusesExistingChildSessionForFollowUp() throws Exception {
        MapDatabaseRepository<RequestInformationCampaign> campaignRepo =
                new MapDatabaseRepository<>(RequestInformationCampaign.class);
        MapDatabaseRepository<RequestInformationResponse> responseRepo =
                new MapDatabaseRepository<>(RequestInformationResponse.class);
        MapDatabaseRepository<AiSession> sessionRepo =
                new MapDatabaseRepository<>(AiSession.class);

        RepositoryFactory repositoryFactory = mock(RepositoryFactory.class);
        when(repositoryFactory.create(RequestInformationCampaign.class)).thenReturn(campaignRepo);
        when(repositoryFactory.create(RequestInformationResponse.class)).thenReturn(responseRepo);
        when(repositoryFactory.create(AiSession.class)).thenReturn(sessionRepo);

        ChannelService channelService = mock(ChannelService.class);
        when(channelService.resolveByChannelName("lee"))
                .thenReturn(Optional.of(new ChannelRef("lee", "Lee", "local")));

        AttentionAlertService attentionAlertService = mock(AttentionAlertService.class);
        when(attentionAlertService.create(any())).thenAnswer(invocation -> {
            AttentionAlertService.CreateAttentionAlertCommand command = invocation.getArgument(0);
            return new AttentionAlert(
                    "alert-1",
                    command.channelNames(),
                    command.alertName(),
                    command.description(),
                    command.resolutionPolicy(),
                    command.actionUrl(),
                    command.attentionAt(),
                    command.sourceType(),
                    command.sourceId(),
                    System.currentTimeMillis(),
                    System.currentTimeMillis());
        });

        BackgroundNotificationService backgroundNotificationService = mock(BackgroundNotificationService.class);

        SystemSettingsService systemSettingsService = mock(SystemSettingsService.class);
        when(systemSettingsService.getGlobal())
                .thenReturn(new SystemSettings("global", "GEMINI", "gemini-2.5-flash", null, 15));

        RequestInformationService service = new RequestInformationService(
                repositoryFactory,
                channelService,
                attentionAlertService,
                backgroundNotificationService,
                systemSettingsService,
                new ObjectMapper().findAndRegisterModules(),
                "");

        setChildSessionRoutingEnabled(service, true);

        String parentSessionUuid = "parent-session-2";
        sessionRepo.save(new AiSession(
                parentSessionUuid,
                "GEMINI",
                SessionOriginMode.WEB,
                "admin",
                "Parent Session",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.AWAITING_INPUT,
                null,
                null,
                null,
                null,
                null));

        ToolSuspensionException.SuspensionCampaign firstCampaign =
                new ToolSuspensionException.SuspensionCampaign(
                        List.of("lee"),
                        RequestResponsePolicy.FIRST,
                        1,
                        false,
                        "First",
                        "First intro",
                        null,
                        0L);

        String firstCampaignUuid = service.ensureCampaignForSuspension(
                parentSessionUuid,
                "event-first",
                "admin",
                "requestInformation",
                "First question?",
                firstCampaign);
        RequestInformationCampaign savedFirst = service.getCampaign(firstCampaignUuid);
        String childSessionUuid = savedFirst.childSessionUuidsByChannel().get("lee");
        assertNotNull(childSessionUuid);

        AiSession firstChildSession = sessionRepo.get(childSessionUuid);
        assertNotNull(firstChildSession);
        int firstMessageCount = firstChildSession.messages().size();

        ToolSuspensionException.SuspensionCampaign secondCampaign =
                new ToolSuspensionException.SuspensionCampaign(
                        List.of("lee"),
                        RequestResponsePolicy.FIRST,
                        1,
                        false,
                        "Second",
                        "Second intro",
                        null,
                        0L);

        String secondCampaignUuid = service.ensureCampaignForSuspension(
                parentSessionUuid,
                "event-second",
                "admin",
                "requestInformation",
                "Second question?",
                secondCampaign);
        RequestInformationCampaign savedSecond = service.getCampaign(secondCampaignUuid);
        String reusedChildSessionUuid = savedSecond.childSessionUuidsByChannel().get("lee");

        assertEquals(childSessionUuid, reusedChildSessionUuid);

        AiSession secondChildSession = sessionRepo.get(reusedChildSessionUuid);
        assertNotNull(secondChildSession);
        assertEquals(secondCampaignUuid,
                secondChildSession.environmentVariables().get("REQUEST_CAMPAIGN_ID"));
        assertTrue(secondChildSession.messages().size() >= firstMessageCount + 2);
        assertEquals("PROMPT_REQUIRED",
                secondChildSession.messages().get(secondChildSession.messages().size() - 1).role());

        UiEventFrame followUpPrompt = new ObjectMapper()
                .readValue(secondChildSession.messages().get(secondChildSession.messages().size() - 1).content(), UiEventFrame.class);
        FormField followUpCampaignIdField = followUpPrompt.formSchema().fields().stream()
                .filter(f -> f != null && "requestCampaignId".equals(f.name()))
                .findFirst()
                .orElse(null);
        assertNotNull(followUpCampaignIdField);
        assertEquals(secondCampaignUuid, followUpCampaignIdField.value());
    }

    private static void setChildSessionRoutingEnabled(RequestInformationService service, boolean enabled) throws Exception {
        java.lang.reflect.Field flag = RequestInformationService.class.getDeclaredField("childSessionRoutingEnabled");
        flag.setAccessible(true);
        flag.set(service, enabled);
    }
}
