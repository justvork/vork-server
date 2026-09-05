package sh.vork.ai.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.http.ResponseEntity;
import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiChatMessage.AttachmentRef;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.ai.service.ChatService;
import sh.vork.ai.terminal.TerminalStreamRouter;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.ai.request.RequestCampaignStatus;
import sh.vork.ai.request.RequestInformationCampaign;
import sh.vork.ai.request.RequestInformationService;
import sh.vork.ai.request.RequestResponsePolicy;
import sh.vork.ai.registry.ToolRegistry;
import sh.vork.binding.BindingCatalogService;
import sh.vork.mcp.service.McpBindingService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionService;
import sh.vork.skill.Skill;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerAttachmentBroadcastTest {

        private static AiSession webSession(String sessionUuid, String username) {
                return new AiSession(
                                sessionUuid,
                                AiProvider.GEMINI.name(),
                                sh.vork.ai.entity.SessionOriginMode.WEB,
                                username,
                                "Session",
                                System.currentTimeMillis(),
                                0,
                                List.of(),
                                AiSession.defaultEnvironmentVariables(),
                                sh.vork.ai.entity.AiSessionStatus.RUNNING,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                List.of());
        }

    @SuppressWarnings("unchecked")
    @Test
    void handleChatMessage_broadcastsAssistantMessageWithAttachments() {
        ChatService chatService = mock(ChatService.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);

        ChatController controller = new ChatController(
                chatService,
                messaging,
                mock(AiOrchestrationService.class),
                mock(TerminalStreamRouter.class),
                mock(ToolRegistry.class),
                (DatabaseRepository<Skill>) mock(DatabaseRepository.class),
                mock(SessionEnvironmentService.class),
                mock(ReflectionService.class),
                mock(BindingCatalogService.class),
                mock(McpBindingService.class),
                mock(RequestInformationService.class)
        );

        String sessionUuid = "session-zip-broadcast";
        AttachmentRef zipAttachment = new AttachmentRef(
                "/api/session-files/download?area=SESSION&sessionUuid=session-zip-broadcast&path=exports%2Fbundle.zip",
                "bundle.zip",
                "application/zip",
                "/api/session-files/download?area=SESSION&sessionUuid=session-zip-broadcast&path=exports%2Fbundle.zip"
        );
        AiChatMessage aiMessage = new AiChatMessage(
                "msg-1",
                "ASSISTANT",
                "Here is your zip file.",
                System.currentTimeMillis(),
                List.of(zipAttachment),
                null,
                null,
                null
        );

        when(chatService.sendMessageAsUser(eq("alice"), eq(sessionUuid), eq("zip this"), any(), any()))
                .thenReturn(aiMessage);

        Principal principal = () -> "alice";
        ChatController.ChatRequest request = new ChatController.ChatRequest(
                sessionUuid,
                "zip this",
                "GEMINI",
                List.of()
        );

        controller.handleChatMessage(request, principal);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(messaging).convertAndSend(eq("/topic/chat/" + sessionUuid), payloadCaptor.capture());

        Object payload = payloadCaptor.getValue();
        assertNotNull(payload);
        AiChatMessage broadcast = (AiChatMessage) payload;
        assertEquals("ASSISTANT", broadcast.role());
        assertNotNull(broadcast.attachments());
        assertEquals(1, broadcast.attachments().size());
        assertEquals("bundle.zip", broadcast.attachments().get(0).name());
        assertEquals("application/zip", broadcast.attachments().get(0).mimeType());
    }

    @SuppressWarnings("unchecked")
    @Test
    void submitChildCampaignResponse_routesThroughExternalRoleApi() {
        ChatService chatService = mock(ChatService.class);
        RequestInformationService requestInformationService = mock(RequestInformationService.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);

        String sessionUuid = "child-session-1";
        String campaignUuid = "campaign-1";
        Map<String, String> env = new java.util.LinkedHashMap<>(AiSession.defaultEnvironmentVariables());
        env.put("REQUEST_CAMPAIGN_ID", campaignUuid);
        env.put("REQUEST_CAMPAIGN_ROUTE_MODE", "CHILD_SESSION");
        env.put("REQUEST_CAMPAIGN_RECIPIENT_CHANNEL", "accounts@example.com");

        AiSession childSession = new AiSession(
                sessionUuid,
                AiProvider.GEMINI.name(),
                sh.vork.ai.entity.SessionOriginMode.WEB,
                "alice",
                "Child",
                System.currentTimeMillis(),
                0,
                List.of(),
                env,
                sh.vork.ai.entity.AiSessionStatus.RUNNING,
                null,
                null,
                List.of(),
                List.of(),
                List.of());

        when(chatService.getSessionForCurrentUser(sessionUuid)).thenReturn(childSession);
        when(chatService.sendMessageAsExternal(eq("alice"), eq(sessionUuid), eq("Customer replied"),
                eq("accounts@example.com"), eq("Information Request"), eq(List.of()), isNull()))
                .thenReturn(new AiChatMessage("m1", "ASSISTANT", "ack", System.currentTimeMillis(), null));

        ChatController controller = new ChatController(
                chatService,
                messaging,
                mock(AiOrchestrationService.class),
                mock(TerminalStreamRouter.class),
                mock(ToolRegistry.class),
                (DatabaseRepository<Skill>) mock(DatabaseRepository.class),
                mock(SessionEnvironmentService.class),
                mock(ReflectionService.class),
                mock(BindingCatalogService.class),
                mock(McpBindingService.class),
                requestInformationService
        );

        ResponseEntity<?> response = controller.submitChildCampaignResponse(
                sessionUuid,
                campaignUuid,
                new ChatController.CampaignResponseRequest("Customer replied"),
                () -> "alice");

        assertEquals(200, response.getStatusCode().value());
        verify(chatService).sendMessageAsExternal(eq("alice"), eq(sessionUuid), eq("Customer replied"),
                eq("accounts@example.com"), eq("Information Request"), eq(List.of()), isNull());
    }

        @SuppressWarnings("unchecked")
        @Test
        void addSessionTool_rejectsDirectReflectionToolId() {
                ChatService chatService = mock(ChatService.class);
                ReflectionService reflectionService = mock(ReflectionService.class);

                when(reflectionService.getReflectionById("reflection-tool-id")).thenReturn(new Reflection(
                                "r-uuid",
                                "reflection-tool-id",
                                "Reflection Tool",
                                "desc",
                                "group-1",
                                List.of(),
                                "GET",
                                "https://example.com",
                                Map.of(),
                                Map.of(),
                                "",
                                "application/json",
                                "application/json",
                                "",
                                1L,
                                System.currentTimeMillis(),
                                System.currentTimeMillis()));

                ChatController controller = new ChatController(
                                chatService,
                                mock(SimpMessagingTemplate.class),
                                mock(AiOrchestrationService.class),
                                mock(TerminalStreamRouter.class),
                                mock(ToolRegistry.class),
                                (DatabaseRepository<Skill>) mock(DatabaseRepository.class),
                                mock(SessionEnvironmentService.class),
                                reflectionService,
                                mock(BindingCatalogService.class),
                                mock(McpBindingService.class),
                                mock(RequestInformationService.class)
                );

                ResponseEntity<?> response = controller.addSessionTool("session-1", "reflection-tool-id");

                assertEquals(400, response.getStatusCode().value());
                @SuppressWarnings("rawtypes")
                Map body = (Map) response.getBody();
                assertEquals("ERROR", body.get("status"));
                assertTrue(String.valueOf(body.get("message")).contains("not directly assignable"));
        }

        @SuppressWarnings("unchecked")
        @Test
        void addSessionTool_allowsNonReflectionToolId() {
                ChatService chatService = mock(ChatService.class);
                ReflectionService reflectionService = mock(ReflectionService.class);

                when(reflectionService.getReflectionById("listFiles")).thenReturn(null);
                AiSession updated = new AiSession(
                                "session-1",
                                AiProvider.GEMINI.name(),
                                sh.vork.ai.entity.SessionOriginMode.WEB,
                                "alice",
                                "Session",
                                System.currentTimeMillis(),
                                0,
                                List.of(),
                                AiSession.defaultEnvironmentVariables(),
                                sh.vork.ai.entity.AiSessionStatus.RUNNING,
                                null,
                                null,
                                List.of(),
                                List.of(),
                                List.of("listFiles"));
                when(chatService.addSessionTool("session-1", "listFiles")).thenReturn(updated);

                ChatController controller = new ChatController(
                                chatService,
                                mock(SimpMessagingTemplate.class),
                                mock(AiOrchestrationService.class),
                                mock(TerminalStreamRouter.class),
                                mock(ToolRegistry.class),
                                (DatabaseRepository<Skill>) mock(DatabaseRepository.class),
                                mock(SessionEnvironmentService.class),
                                reflectionService,
                                mock(BindingCatalogService.class),
                                mock(McpBindingService.class),
                                mock(RequestInformationService.class)
                );

                ResponseEntity<?> response = controller.addSessionTool("session-1", "listFiles");

                assertEquals(200, response.getStatusCode().value());
                @SuppressWarnings("rawtypes")
                Map body = (Map) response.getBody();
                assertEquals("OK", body.get("status"));
        }

        @SuppressWarnings("unchecked")
        @Test
        void getActiveRequestCampaign_returnsOpenCampaign() {
                ChatService chatService = mock(ChatService.class);
                RequestInformationService requestInformationService = mock(RequestInformationService.class);
                String sessionUuid = "session-open-1";

                when(chatService.getSessionForCurrentUser(sessionUuid)).thenReturn(webSession(sessionUuid, "alice"));
                when(requestInformationService.findOpenCampaignForSession(sessionUuid)).thenReturn(new RequestInformationCampaign(
                        "campaign-1",
                        sessionUuid,
                        "event-1",
                        "alice",
                        "Need approval from admins",
                        List.of("bob", "carol"),
                        RequestResponsePolicy.QUORUM,
                        2,
                        List.of("bob"),
                        RequestCampaignStatus.OPEN,
                        false,
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                        null
                ));

                ChatController controller = new ChatController(
                        chatService,
                        mock(SimpMessagingTemplate.class),
                        mock(AiOrchestrationService.class),
                        mock(TerminalStreamRouter.class),
                        mock(ToolRegistry.class),
                        (DatabaseRepository<Skill>) mock(DatabaseRepository.class),
                        mock(SessionEnvironmentService.class),
                        mock(ReflectionService.class),
                        mock(BindingCatalogService.class),
                        mock(McpBindingService.class),
                        requestInformationService
                );

                ResponseEntity<?> response = controller.getActiveRequestCampaign(sessionUuid);

                assertEquals(200, response.getStatusCode().value());
                Map<String, Object> body = (Map<String, Object>) response.getBody();
                assertEquals("OPEN", body.get("status"));
                assertEquals("campaign-1", body.get("campaignUuid"));
                assertEquals(2, body.get("requiredResponses"));
                assertEquals(1, body.get("respondedCount"));
        }

        @SuppressWarnings("unchecked")
        @Test
        void getActiveRequestCampaign_returnsNoneWhenNoOpenCampaign() {
                ChatService chatService = mock(ChatService.class);
                RequestInformationService requestInformationService = mock(RequestInformationService.class);
                String sessionUuid = "session-none-1";

                when(chatService.getSessionForCurrentUser(sessionUuid)).thenReturn(webSession(sessionUuid, "alice"));
                when(requestInformationService.findOpenCampaignForSession(sessionUuid)).thenReturn(null);

                ChatController controller = new ChatController(
                        chatService,
                        mock(SimpMessagingTemplate.class),
                        mock(AiOrchestrationService.class),
                        mock(TerminalStreamRouter.class),
                        mock(ToolRegistry.class),
                        (DatabaseRepository<Skill>) mock(DatabaseRepository.class),
                        mock(SessionEnvironmentService.class),
                        mock(ReflectionService.class),
                        mock(BindingCatalogService.class),
                        mock(McpBindingService.class),
                        requestInformationService
                );

                ResponseEntity<?> response = controller.getActiveRequestCampaign(sessionUuid);
                Map<String, Object> body = (Map<String, Object>) response.getBody();

                assertEquals(200, response.getStatusCode().value());
                assertEquals("NONE", body.get("status"));
        }

        @SuppressWarnings("unchecked")
        @Test
        void cancelRequestCampaign_cancelsAndBroadcasts() {
                ChatService chatService = mock(ChatService.class);
                RequestInformationService requestInformationService = mock(RequestInformationService.class);
                SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
                String sessionUuid = "session-cancel-1";
                String campaignUuid = "campaign-cancel-1";

                when(chatService.getSessionForCurrentUser(sessionUuid)).thenReturn(webSession(sessionUuid, "alice"));
                when(requestInformationService.getCampaign(campaignUuid)).thenReturn(new RequestInformationCampaign(
                        campaignUuid,
                        sessionUuid,
                        "event-cancel-1",
                        "alice",
                        "Need confirmation",
                        List.of("bob"),
                        RequestResponsePolicy.ALL,
                        1,
                        List.of(),
                        RequestCampaignStatus.OPEN,
                        false,
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                        null
                ));
                when(requestInformationService.cancelCampaign(campaignUuid)).thenReturn(true);

                ChatController controller = new ChatController(
                        chatService,
                        messaging,
                        mock(AiOrchestrationService.class),
                        mock(TerminalStreamRouter.class),
                        mock(ToolRegistry.class),
                        (DatabaseRepository<Skill>) mock(DatabaseRepository.class),
                        mock(SessionEnvironmentService.class),
                        mock(ReflectionService.class),
                        mock(BindingCatalogService.class),
                        mock(McpBindingService.class),
                        requestInformationService
                );

                ResponseEntity<?> response = controller.cancelRequestCampaign(sessionUuid, campaignUuid);
                Map<String, Object> body = (Map<String, Object>) response.getBody();

                assertEquals(200, response.getStatusCode().value());
                assertEquals("CANCELLED", body.get("status"));
                verify(chatService).releaseAwaitingInputSession(eq(sessionUuid), any());
                verify(messaging).convertAndSend(eq("/topic/chat/" + sessionUuid), org.mockito.ArgumentMatchers.<Object>any());
        }

        @SuppressWarnings("unchecked")
        @Test
        void cancelRequestCampaign_rejectsCampaignFromDifferentSession() {
                ChatService chatService = mock(ChatService.class);
                RequestInformationService requestInformationService = mock(RequestInformationService.class);
                SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
                String sessionUuid = "session-cancel-2";
                String campaignUuid = "campaign-cancel-2";

                when(chatService.getSessionForCurrentUser(sessionUuid)).thenReturn(webSession(sessionUuid, "alice"));
                when(requestInformationService.getCampaign(campaignUuid)).thenReturn(new RequestInformationCampaign(
                        campaignUuid,
                        "other-session",
                        "event-cancel-2",
                        "alice",
                        "Need confirmation",
                        List.of("bob"),
                        RequestResponsePolicy.ALL,
                        1,
                        List.of(),
                        RequestCampaignStatus.OPEN,
                        false,
                        System.currentTimeMillis(),
                        System.currentTimeMillis(),
                        null
                ));

                ChatController controller = new ChatController(
                        chatService,
                        messaging,
                        mock(AiOrchestrationService.class),
                        mock(TerminalStreamRouter.class),
                        mock(ToolRegistry.class),
                        (DatabaseRepository<Skill>) mock(DatabaseRepository.class),
                        mock(SessionEnvironmentService.class),
                        mock(ReflectionService.class),
                        mock(BindingCatalogService.class),
                        mock(McpBindingService.class),
                        requestInformationService
                );

                ResponseEntity<?> response = controller.cancelRequestCampaign(sessionUuid, campaignUuid);
                Map<String, Object> body = (Map<String, Object>) response.getBody();

                assertEquals(403, response.getStatusCode().value());
                assertEquals("ERROR", body.get("status"));
                verify(requestInformationService, never()).cancelCampaign(any());
                verify(chatService, never()).releaseAwaitingInputSession(any(), any());
                verify(messaging, never()).convertAndSend(anyString(), org.mockito.ArgumentMatchers.<Object>any());
        }

        @SuppressWarnings("unchecked")
        @Test
        void listSessions_forwardsAgentSearchAndLimit() {
                ChatService chatService = mock(ChatService.class);
                when(chatService.listSessionsForCurrentUser("agent-marketing-001", "campaign", 5))
                        .thenReturn(List.of(webSession("session-1", "alice")));

                ChatController controller = new ChatController(
                        chatService,
                        mock(SimpMessagingTemplate.class),
                        mock(AiOrchestrationService.class),
                        mock(TerminalStreamRouter.class),
                        mock(ToolRegistry.class),
                        (DatabaseRepository<Skill>) mock(DatabaseRepository.class),
                        mock(SessionEnvironmentService.class),
                        mock(ReflectionService.class),
                        mock(BindingCatalogService.class),
                        mock(McpBindingService.class),
                        mock(RequestInformationService.class)
                );

                List<ChatController.SessionSummaryResponse> response = controller.listSessions(
                        "agent-marketing-001",
                        "campaign",
                        5);

                assertEquals(1, response.size());
                verify(chatService).listSessionsForCurrentUser("agent-marketing-001", "campaign", 5);
        }

        @SuppressWarnings("unchecked")
        @Test
        void createSession_passesRequestedAgentTemplateId() {
                ChatService chatService = mock(ChatService.class);
                SessionEnvironmentService sessionEnvironmentService = mock(SessionEnvironmentService.class);

                when(chatService.createNewSession(AiProvider.GEMINI, null, "agent-marketing-001"))
                        .thenReturn(webSession("session-2", "alice"));
                when(sessionEnvironmentService.getEnv("session-2")).thenReturn(Map.of());

                ChatController controller = new ChatController(
                        chatService,
                        mock(SimpMessagingTemplate.class),
                        mock(AiOrchestrationService.class),
                        mock(TerminalStreamRouter.class),
                        mock(ToolRegistry.class),
                        (DatabaseRepository<Skill>) mock(DatabaseRepository.class),
                        sessionEnvironmentService,
                        mock(ReflectionService.class),
                        mock(BindingCatalogService.class),
                        mock(McpBindingService.class),
                        mock(RequestInformationService.class)
                );

                jakarta.servlet.http.HttpServletRequest request = mock(jakarta.servlet.http.HttpServletRequest.class);
                when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");
                when(request.getHeader("X-Forwarded-Host")).thenReturn("example.test");
                when(request.getHeader("X-Forwarded-Port")).thenReturn("443");
                when(request.getContextPath()).thenReturn("");

                ChatController.SessionResponse response = controller.createSession(
                        request,
                        AiProvider.GEMINI,
                        null,
                        "agent-marketing-001");

                assertNotNull(response);
                assertEquals("session-2", response.sessionUuid());
                verify(chatService).createNewSession(AiProvider.GEMINI, null, "agent-marketing-001");
        }
}
