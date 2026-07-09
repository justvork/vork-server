package sh.vork.ai.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiChatMessage.AttachmentRef;
import sh.vork.ai.service.AiOrchestrationService;
import sh.vork.ai.service.ChatService;
import sh.vork.ai.terminal.TerminalStreamRouter;
import sh.vork.ai.memory.SessionEnvironmentService;
import sh.vork.ai.provider.AiModelService;
import sh.vork.ai.registry.ToolRegistry;
import sh.vork.orm.DatabaseRepository;
import sh.vork.skill.Skill;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerAttachmentBroadcastTest {

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
                mock(AiModelService.class),
                mock(ToolRegistry.class),
                (DatabaseRepository<Skill>) mock(DatabaseRepository.class),
                mock(SessionEnvironmentService.class)
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

        when(chatService.sendMessageAsUser(eq("alice"), eq(sessionUuid), eq("zip this"), any(), eq(AiProvider.GEMINI)))
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
}
