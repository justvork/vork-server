package sh.vork.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.security.LoggedToolCallback;
import sh.vork.ai.security.VisualizableToolCallback;
import sh.vork.ai.telegram.TelegramChatResumptionService;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.ai.request.RequestCampaignStatus;
import sh.vork.ai.request.RequestInformationCampaign;
import sh.vork.ai.request.RequestInformationService;
import sh.vork.ai.request.RequestResponsePolicy;
import sh.vork.ai.request.RequestResponseRouteMode;
import sh.vork.relay.RelayEncryptionService;
import sh.vork.relay.RelayHttpClient;
import sh.vork.scheduling.service.SystemNotificationService;
import sh.vork.setup.SystemSettingsService;

class ChatServiceSuspensionPersistenceTest {

    @Test
    void sendMessageAsUser_whenChildCampaignQuorumNotMet_recordsResponseWithoutParentResume() {
    MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
    AiOrchestrationService aiService = mock(AiOrchestrationService.class);
    SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    TelegramChatResumptionService telegramChatResumptionService = mock(TelegramChatResumptionService.class);
    RequestInformationService requestInformationService = mock(RequestInformationService.class);

    String parentSessionId = "parent-session-quorum";
    String childSessionId = "child-session-quorum";
    String campaignId = "campaign-quorum";
    String eventId = "event-quorum";

    AiSession parent = new AiSession(
        parentSessionId,
        AiProvider.GEMINI.name(),
        SessionOriginMode.WEB,
        "admin",
        "Parent",
        100L,
        0,
        List.of(),
        AiSession.defaultEnvironmentVariables(),
        AiSessionStatus.AWAITING_INPUT,
        null,
        null,
        null,
        null,
        null);
    sessionRepo.save(parent);

    java.util.Map<String, String> childEnv = new java.util.HashMap<>(AiSession.defaultEnvironmentVariables());
    childEnv.put("REQUEST_CAMPAIGN_ID", campaignId);
    childEnv.put("REQUEST_CAMPAIGN_PARENT_SESSION_UUID", parentSessionId);
    childEnv.put("REQUEST_CAMPAIGN_RECIPIENT_CHANNEL", "alice");
    childEnv.put("REQUEST_CAMPAIGN_ROUTE_MODE", "CHILD_SESSION");

    AiSession child = new AiSession(
        childSessionId,
        AiProvider.GEMINI.name(),
        SessionOriginMode.WEB,
        "alice",
        "Child",
        101L,
        0,
        List.of(),
        childEnv,
        AiSessionStatus.RUNNING,
        null,
        null,
        null,
        null,
        null);
    sessionRepo.save(child);

    RequestInformationCampaign campaign = new RequestInformationCampaign(
        campaignId,
        parentSessionId,
        eventId,
        "admin",
        "Need input",
        List.of("alice", "bob"),
        RequestResponsePolicy.ALL,
        2,
        List.of(),
        RequestCampaignStatus.OPEN,
        false,
        200L,
        200L,
        null,
        parentSessionId,
        java.util.Map.of("alice", childSessionId),
        RequestResponseRouteMode.CHILD_SESSION,
        true);

    RequestInformationService.ResponseGateResult gate = new RequestInformationService.ResponseGateResult(
        true,
        false,
        campaignId,
        1,
        2,
        "Thanks. Response recorded (1/2).");

    when(requestInformationService.getCampaign(campaignId)).thenReturn(campaign);
    when(requestInformationService.recordResponseAndEvaluate(eq(campaignId), eq("alice"), eq("ONCE"), any()))
        .thenReturn(gate);

    ChatService chatService = new ChatService(
        sessionRepo,
        null,
        aiService,
        messaging,
        objectMapper,
        List.of(),
        mock(SystemNotificationService.class),
        Runnable::run,
        mock(RelayEncryptionService.class),
        mock(RelayHttpClient.class),
        mock(SystemSettingsService.class),
        telegramChatResumptionService);

    ReflectionTestUtils.setField(chatService, "requestInformationService", requestInformationService);

    AiChatMessage out = chatService.sendMessageAsUser("alice", childSessionId, "first answer", null, AiProvider.GEMINI);

    assertNotNull(out);
    assertEquals("ASSISTANT", out.role());
    assertEquals("Thanks. Response recorded (1/2).", out.content());

    AiSession savedChild = sessionRepo.get(childSessionId);
    assertNotNull(savedChild);
    assertEquals(2, savedChild.messages().size());
    assertEquals("USER", savedChild.messages().get(0).role());
    assertEquals("first answer", savedChild.messages().get(0).content());
    assertEquals("ASSISTANT", savedChild.messages().get(1).role());

    verify(requestInformationService).recordResponseAndEvaluate(eq(campaignId), eq("alice"), eq("ONCE"), any());
    verify(requestInformationService, never()).markResumeStarted(anyString());
    verify(requestInformationService, never()).buildResumeFields(anyString());
    verifyNoInteractions(telegramChatResumptionService);
    verify(aiService, never()).generateWithHistory(any(), anyString(), any(), anyString());
    verifyNoInteractions(messaging);
    }

    @Test
    void sendMessageAsUser_whenChildCampaignSession_recordsResponseAndResumesParentWithoutChildAiCall() {
    MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
    AiOrchestrationService aiService = mock(AiOrchestrationService.class);
    SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    TelegramChatResumptionService telegramChatResumptionService = mock(TelegramChatResumptionService.class);
    RequestInformationService requestInformationService = mock(RequestInformationService.class);

    String parentSessionId = "parent-session";
    String childSessionId = "child-session";
    String campaignId = "campaign-1";
    String eventId = "event-1";

    AiSession parent = new AiSession(
        parentSessionId,
        AiProvider.GEMINI.name(),
        SessionOriginMode.WEB,
        "admin",
        "Parent",
        100L,
        0,
        List.of(),
        AiSession.defaultEnvironmentVariables(),
        AiSessionStatus.AWAITING_INPUT,
        null,
        null,
        null,
        null,
        null);
    sessionRepo.save(parent);

    java.util.Map<String, String> childEnv = new java.util.HashMap<>(AiSession.defaultEnvironmentVariables());
    childEnv.put("REQUEST_CAMPAIGN_ID", campaignId);
    childEnv.put("REQUEST_CAMPAIGN_PARENT_SESSION_UUID", parentSessionId);
    childEnv.put("REQUEST_CAMPAIGN_RECIPIENT_CHANNEL", "alice");
    childEnv.put("REQUEST_CAMPAIGN_ROUTE_MODE", "CHILD_SESSION");

    AiSession child = new AiSession(
        childSessionId,
        AiProvider.GEMINI.name(),
        SessionOriginMode.WEB,
        "alice",
        "Child",
        101L,
        0,
        List.of(),
        childEnv,
        AiSessionStatus.RUNNING,
        null,
        null,
        null,
        null,
        null);
    sessionRepo.save(child);

    RequestInformationCampaign campaign = new RequestInformationCampaign(
        campaignId,
        parentSessionId,
        eventId,
        "admin",
        "Need input",
        List.of("alice"),
        RequestResponsePolicy.FIRST,
        1,
        List.of(),
        RequestCampaignStatus.OPEN,
        false,
        200L,
        200L,
        null,
        parentSessionId,
        java.util.Map.of("alice", childSessionId),
        RequestResponseRouteMode.CHILD_SESSION,
        true);

    RequestInformationService.ResponseGateResult gate = new RequestInformationService.ResponseGateResult(
        true,
        true,
        campaignId,
        1,
        1,
        "Thanks. Required responses received; processing will continue.");

    when(requestInformationService.getCampaign(campaignId)).thenReturn(campaign);
    when(requestInformationService.recordResponseAndEvaluate(eq(campaignId), eq("alice"), eq("ONCE"), any()))
        .thenReturn(gate);
    when(requestInformationService.markResumeStarted(campaignId)).thenReturn(true);
    when(requestInformationService.buildResumeFields(campaignId)).thenReturn(java.util.Map.of(
        "requestCampaignId", campaignId,
        "responsesJson", "[]",
        "responseCount", "1"));
    when(telegramChatResumptionService.resumeAndRun(
        eq("admin"),
        eq(parentSessionId),
        eq(eventId),
        eq("ONCE"),
        any())).thenReturn("Parent resumed output");

    ChatService chatService = new ChatService(
        sessionRepo,
        null,
        aiService,
        messaging,
        objectMapper,
        List.of(),
        mock(SystemNotificationService.class),
        Runnable::run,
        mock(RelayEncryptionService.class),
        mock(RelayHttpClient.class),
        mock(SystemSettingsService.class),
        telegramChatResumptionService);

    ReflectionTestUtils.setField(chatService, "requestInformationService", requestInformationService);

    AiChatMessage out = chatService.sendMessageAsUser("alice", childSessionId, "my answer", null, AiProvider.GEMINI);

    assertNotNull(out);
    assertEquals("ASSISTANT", out.role());
    assertEquals("Thanks. Required responses received; processing will continue.", out.content());

    AiSession savedChild = sessionRepo.get(childSessionId);
    assertNotNull(savedChild);
    assertEquals(2, savedChild.messages().size());
    assertEquals("USER", savedChild.messages().get(0).role());
    assertEquals("my answer", savedChild.messages().get(0).content());
    assertEquals("ASSISTANT", savedChild.messages().get(1).role());

    verify(requestInformationService).recordResponseAndEvaluate(eq(campaignId), eq("alice"), eq("ONCE"), any());
    verify(requestInformationService).markResumeStarted(campaignId);
    verify(requestInformationService).buildResumeFields(campaignId);
    verify(telegramChatResumptionService).resumeAndRun(eq("admin"), eq(parentSessionId), eq(eventId), eq("ONCE"), any());
    verify(messaging).convertAndSend(eq("/topic/chat/" + parentSessionId), any(UiEventFrame.class));
    verify(aiService, never()).generateWithHistory(any(), anyString(), any(), anyString());
    }

    @Test
    void sendMessageAsUser_whenParentResumeResuspendsRequestInformation_suppressesRequesterPromptBroadcast() {
    MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
    AiOrchestrationService aiService = mock(AiOrchestrationService.class);
    SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    TelegramChatResumptionService telegramChatResumptionService = mock(TelegramChatResumptionService.class);
    RequestInformationService requestInformationService = mock(RequestInformationService.class);

    String parentSessionId = "parent-session-resuspend";
    String childSessionId = "child-session-resuspend";
    String campaignId = "campaign-resuspend";
    String eventId = "event-resuspend";

    AiSession parent = new AiSession(
        parentSessionId,
        AiProvider.GEMINI.name(),
        SessionOriginMode.WEB,
        "admin",
        "Parent",
        100L,
        0,
        List.of(),
        AiSession.defaultEnvironmentVariables(),
        AiSessionStatus.AWAITING_INPUT,
        null,
        null,
        null,
        null,
        null);
    sessionRepo.save(parent);

    java.util.Map<String, String> childEnv = new java.util.HashMap<>(AiSession.defaultEnvironmentVariables());
    childEnv.put("REQUEST_CAMPAIGN_ID", campaignId);
    childEnv.put("REQUEST_CAMPAIGN_PARENT_SESSION_UUID", parentSessionId);
    childEnv.put("REQUEST_CAMPAIGN_RECIPIENT_CHANNEL", "alice");
    childEnv.put("REQUEST_CAMPAIGN_ROUTE_MODE", "CHILD_SESSION");

    AiSession child = new AiSession(
        childSessionId,
        AiProvider.GEMINI.name(),
        SessionOriginMode.WEB,
        "alice",
        "Child",
        101L,
        0,
        List.of(),
        childEnv,
        AiSessionStatus.RUNNING,
        null,
        null,
        null,
        null,
        null);
    sessionRepo.save(child);

    RequestInformationCampaign campaign = new RequestInformationCampaign(
        campaignId,
        parentSessionId,
        eventId,
        "admin",
        "Need input",
        List.of("alice"),
        RequestResponsePolicy.FIRST,
        1,
        List.of(),
        RequestCampaignStatus.OPEN,
        false,
        200L,
        200L,
        null,
        parentSessionId,
        java.util.Map.of("alice", childSessionId),
        RequestResponseRouteMode.CHILD_SESSION,
        true);

    RequestInformationService.ResponseGateResult gate = new RequestInformationService.ResponseGateResult(
        true,
        true,
        campaignId,
        1,
        1,
        "Thanks. Required responses received; processing will continue.");

    when(requestInformationService.getCampaign(campaignId)).thenReturn(campaign);
    when(requestInformationService.recordResponseAndEvaluate(eq(campaignId), eq("alice"), eq("ONCE"), any()))
        .thenReturn(gate);
    when(requestInformationService.markResumeStarted(campaignId)).thenReturn(true);
    when(requestInformationService.buildResumeFields(campaignId)).thenReturn(java.util.Map.of(
        "requestCampaignId", campaignId,
        "responsesJson", "[]",
        "responseCount", "1"));
    when(requestInformationService.ensureCampaignForSuspension(
        eq(parentSessionId),
        anyString(),
        eq("admin"),
        eq("requestInformation"),
        eq("Please provide a number between 1 and 10 other than 7."),
        any())).thenReturn("campaign-follow-up");

    ToolSuspensionException.SuspensionCampaign followUpCampaign = new ToolSuspensionException.SuspensionCampaign(
        List.of("lee"),
        RequestResponsePolicy.FIRST,
        1,
        true,
        "Another Number Requested",
        "Hi Lee, please provide another number.",
        "ACTION_REQUIRED",
        0L);
    ToolSuspensionException followUpSuspension = new ToolSuspensionException(
        "requestInformation",
        "{\"channelNames\":[\"lee\"],\"promptText\":\"Please provide a number between 1 and 10 other than 7.\",\"requesterMessage\":\"I am asking Lee for another number.\",\"recipientMessage\":\"Hi Lee, please provide another number.\",\"responsePolicy\":\"FIRST\",\"quorumCount\":1}",
        "I am asking Lee for another number.",
        null,
        followUpCampaign);

    when(telegramChatResumptionService.resumeAndRun(
        eq("admin"),
        eq(parentSessionId),
        eq(eventId),
        eq("ONCE"),
        any())).thenThrow(followUpSuspension);

    ChatService chatService = new ChatService(
        sessionRepo,
        null,
        aiService,
        messaging,
        objectMapper,
        List.of(),
        mock(SystemNotificationService.class),
        Runnable::run,
        mock(RelayEncryptionService.class),
        mock(RelayHttpClient.class),
        mock(SystemSettingsService.class),
        telegramChatResumptionService);

    ReflectionTestUtils.setField(chatService, "requestInformationService", requestInformationService);

    AiChatMessage out = chatService.sendMessageAsUser("alice", childSessionId, "7", null, AiProvider.GEMINI);

    assertNotNull(out);
    assertEquals("ASSISTANT", out.role());
    assertEquals("Thanks. Required responses received; processing will continue.", out.content());

    verify(telegramChatResumptionService).resumeAndRun(eq("admin"), eq(parentSessionId), eq(eventId), eq("ONCE"), any());
    verify(requestInformationService).ensureCampaignForSuspension(
        eq(parentSessionId),
        anyString(),
        eq("admin"),
        eq("requestInformation"),
        eq("Please provide a number between 1 and 10 other than 7."),
        any());
    verify(messaging, never()).convertAndSend(eq("/topic/chat/" + parentSessionId), any(UiEventFrame.class));
    verify(aiService, never()).generateWithHistory(any(), anyString(), any(), anyString());
    }

    @Test
    void sendMessage_whenProviderWrapsSuspensionAsRuntime_recoversAndPersistsAwaitingInput() {
    MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
    AiOrchestrationService aiService = mock(AiOrchestrationService.class);
    SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    String sessionId = "session-runtime-wrap";
    AiSession initial = new AiSession(
        sessionId,
        AiProvider.GEMINI.name(),
        SessionOriginMode.WEB,
        "anonymous",
        "Untitled",
        123L,
        0,
        List.of(),
        AiSession.defaultEnvironmentVariables(),
        AiSessionStatus.RUNNING,
        null,
        null,
        null,
        null,
        null);
    sessionRepo.save(initial);

    when(aiService.generateWithHistoryStrict(org.mockito.ArgumentMatchers.<org.springframework.ai.chat.messages.Message>anyList(),
        anyString(), any(AiProvider.class), anyString()))
        .thenAnswer(invocation -> {
            ToolExecutionContext.put(
                LoggedToolCallback.PENDING_TOOL_SUSPENSION_CONTEXT_KEY,
                new ToolSuspensionException("compileJavaType", "{\"source\":\"class Demo {}\"}"));
            throw new RuntimeException("Failed to parse JSON: Tool execution suspended pending authorization");
        });

    ToolCallback compileDelegate = mock(ToolCallback.class);
    ToolDefinition def = mock(ToolDefinition.class);
    when(def.name()).thenReturn("compileJavaType");
    when(compileDelegate.getToolDefinition()).thenReturn(def);
    ToolCallback compileTool = new VisualizableToolCallback(
        compileDelegate,
        args -> "```java\nclass Demo {}\n```"
    );

    ChatService chatService = new ChatService(
        sessionRepo,
        null,
        aiService,
        messaging,
        objectMapper,
        List.of(compileTool),
        mock(SystemNotificationService.class),
        Runnable::run,
        mock(RelayEncryptionService.class),
        mock(RelayHttpClient.class),
        mock(SystemSettingsService.class),
        null);

        try {
            AiChatMessage out = chatService.sendMessage(sessionId, "please compile", null, AiProvider.GEMINI);

            assertNull(out, "Chat turn should end with suspension handling when runtime wrapper is recovered");

            AiSession saved = sessionRepo.get(sessionId);
            assertNotNull(saved);
            assertEquals(AiSessionStatus.AWAITING_INPUT, saved.status());
            assertEquals(2, saved.messages().size());
            assertEquals("PROMPT_REQUIRED", saved.messages().get(1).role());
        } finally {
            ToolExecutionContext.complete(sessionId);
        }
    }

    @Test
    void sendMessage_whenToolSuspended_persistsAwaitingAuthorizationSnapshot() throws Exception {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        AiOrchestrationService aiService = mock(AiOrchestrationService.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

        String sessionId = "session-1";
        AiSession initial = new AiSession(
            sessionId,
            AiProvider.GEMINI.name(),
            SessionOriginMode.WEB,
                "anonymous",
            "Untitled",
            123L,
            0,
            List.of(),
            AiSession.defaultEnvironmentVariables(),
            AiSessionStatus.RUNNING,
            null,
            null,
            null,
            null,
            null);
        sessionRepo.save(initial);

        when(aiService.generateWithHistoryStrict(org.mockito.ArgumentMatchers.<org.springframework.ai.chat.messages.Message>anyList(),
            anyString(), any(AiProvider.class), anyString()))
                .thenThrow(new ToolSuspensionException("compileJavaType", "{\"source\":\"class Demo {}\"}"));

        // Ensure media path is not accidentally used in this scenario.
        when(aiService.generateWithHistoryAndMediaStrict(
            org.mockito.ArgumentMatchers.<org.springframework.ai.chat.messages.Message>anyList(),
            anyString(),
            org.mockito.ArgumentMatchers.<org.springframework.ai.content.Media>anyList(),
            any(AiProvider.class),
            anyString()))
                .thenReturn("unused");

        ToolCallback compileDelegate = mock(ToolCallback.class);
        ToolDefinition def = mock(ToolDefinition.class);
        when(def.name()).thenReturn("compileJavaType");
        when(compileDelegate.getToolDefinition()).thenReturn(def);
        ToolCallback compileTool = new VisualizableToolCallback(
            compileDelegate,
            args -> "```java\nclass Demo {}\n```"
        );

        ChatService chatService = new ChatService(
            sessionRepo,
            null,
            aiService,
            messaging,
            objectMapper,
            List.of(compileTool),
            mock(SystemNotificationService.class),
            Runnable::run,
            mock(RelayEncryptionService.class),
            mock(RelayHttpClient.class),
            mock(SystemSettingsService.class),
            null);

        AiChatMessage out = chatService.sendMessage(sessionId, "please compile", null, AiProvider.GEMINI);

        assertNull(out, "Chat turn should terminate with null when authorization is required");

        AiSession saved = sessionRepo.get(sessionId);
        assertNotNull(saved);
        assertEquals(AiSessionStatus.AWAITING_INPUT, saved.status());
        assertEquals(2, saved.messages().size(), "Expected persisted USER + PROMPT_REQUIRED messages");

        AiChatMessage user = saved.messages().get(0);
        assertEquals("USER", user.role());
        assertEquals("please compile", user.content());

        AiChatMessage awaiting = saved.messages().get(1);
        assertEquals("PROMPT_REQUIRED", awaiting.role());
        UiEventFrame frame = objectMapper.readValue(awaiting.content(), UiEventFrame.class);
        assertEquals("PROMPT_REQUIRED", frame.type());
        assertEquals("AUTHORIZE_TOOL", frame.intent());
        assertEquals("Approval is required to compile and register a new Java type so it can be used in later steps.",
            frame.textResponse());
        assertNotNull(frame.formSchema());
        assertEquals("AUTHORIZE_TOOL", frame.formSchema().intent());
        assertEquals(List.of("ONCE", "SESSION", "ALWAYS", "DENIED"),
            frame.formSchema().actions().stream().map(a -> a.name()).toList());
        assertEquals("Confirm whether this protected tool call should run.",
            frame.formSchema().description());
        assertNotNull(awaiting.toolCalls());
        assertEquals(1, awaiting.toolCalls().size());

        AiChatMessage.ToolCallRef tool = awaiting.toolCalls().get(0);
        assertEquals("FUNCTION", tool.type());
        assertEquals("compileJavaType", tool.name());
        assertEquals("{\"source\":\"class Demo {}\"}", tool.arguments());
        assertTrue(tool.id().startsWith("pending-"));
        assertEquals(tool.id(), awaiting.toolCallId());
        assertEquals("compileJavaType", awaiting.toolName());

        verify(messaging).convertAndSend(anyString(), any(UiEventFrame.class));
    }
}
