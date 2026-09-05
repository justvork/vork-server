package sh.vork.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.relay.RelayEncryptionService;
import sh.vork.relay.RelayHttpClient;
import sh.vork.scheduling.service.SystemNotificationService;
import sh.vork.setup.SystemSettingsService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceSessionAttachmentTokenTest {

    @Test
    void sendMessageAsUser_resolvesSessionUrlAttachmentAndInjectsTextIntoPrompt() throws Exception {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        AiOrchestrationService aiService = mock(AiOrchestrationService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);

        String sessionUuid = "session-attachment-token";
        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING,
                null,
                null,
                List.of(),
                List.of(),
                List.of()));

        String downloadUrl = "/api/session-files/download?area=SESSION&sessionUuid=" + sessionUuid + "&path=docs%2Fnote.txt";
        String attachmentToken = "session-url:" + downloadUrl;
        String attachedText = "hello from attached session file";

        when(sessionFileSystem.read(eq(FileArea.SESSION), eq(sessionUuid), eq("docs/note.txt")))
                .thenReturn(new ByteArrayInputStream(attachedText.getBytes(StandardCharsets.UTF_8)));
        when(aiService.generateWithHistoryStrict(anyList(), any(String.class), eq(AiProvider.GEMINI), nullable(String.class)))
                .thenReturn("{\"status\":\"FINISHED_TURN\",\"textResponse\":\"ok\"}");

        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                aiService,
                sessionFileSystem,
                mock(SimpMessagingTemplate.class),
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                mock(SystemNotificationService.class),
                Runnable::run,
                mock(RelayEncryptionService.class),
                mock(RelayHttpClient.class),
                mock(SystemSettingsService.class),
                null);

        AiChatMessage response = chatService.sendMessageAsUser(
                "alice",
                sessionUuid,
                "please summarize",
                List.of(attachmentToken),
                AiProvider.GEMINI);

        assertNotNull(response);
        assertEquals("ok", response.content());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).generateWithHistoryStrict(anyList(), promptCaptor.capture(), eq(AiProvider.GEMINI), nullable(String.class));
        String effectivePrompt = promptCaptor.getValue();
        assertTrue(effectivePrompt.contains("[Attached file: note.txt]"));
        assertTrue(effectivePrompt.contains(attachedText));
        assertTrue(effectivePrompt.contains("please summarize"));

        AiSession saved = sessionRepo.get(sessionUuid);
        assertNotNull(saved);
        assertEquals(2, saved.messages().size());
        AiChatMessage user = saved.messages().get(0);
        assertEquals("USER", user.role());
        assertNotNull(user.attachments());
        assertEquals(1, user.attachments().size());
        assertEquals("note.txt", user.attachments().get(0).name());
        assertEquals(downloadUrl, user.attachments().get(0).url());

                AiChatMessage assistant = saved.messages().get(1);
                assertEquals("ASSISTANT", assistant.role());
                assertEquals(null, assistant.attachments());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void sendMessageAsUser_replaysExternalHistoryAsWrappedUserEvidence() throws Exception {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        AiOrchestrationService aiService = mock(AiOrchestrationService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);

        String sessionUuid = "session-external-history";
        AiChatMessage external = new AiChatMessage(
                "external-1",
                "EXTERNAL",
                "Please process invoice 42.",
                System.currentTimeMillis(),
                null,
                "email",
                "accounts@example.com");

        sessionRepo.save(new AiSession(
                sessionUuid,
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(external),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING,
                null,
                null,
                List.of(),
                List.of(),
                List.of()));

        when(aiService.generateWithHistoryStrict(anyList(), any(String.class), eq(AiProvider.GEMINI), nullable(String.class)))
                .thenReturn("{\"status\":\"FINISHED_TURN\",\"textResponse\":\"ok\"}");

        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                aiService,
                sessionFileSystem,
                mock(SimpMessagingTemplate.class),
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                mock(SystemNotificationService.class),
                Runnable::run,
                mock(RelayEncryptionService.class),
                mock(RelayHttpClient.class),
                mock(SystemSettingsService.class),
                null);

        chatService.sendMessageAsUser("alice", sessionUuid, "summarize", List.of(), AiProvider.GEMINI);

        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass((Class) List.class);
        verify(aiService).generateWithHistoryStrict(historyCaptor.capture(), any(String.class), eq(AiProvider.GEMINI), nullable(String.class));

        List<Message> history = historyCaptor.getValue();
        assertEquals(1, history.size());
        Message first = history.getFirst();
        assertTrue(first instanceof UserMessage);
        String wrapped = first.getText();
        assertTrue(wrapped.contains("<external-message"));
        assertTrue(wrapped.contains("source=\"email\""));
        assertTrue(wrapped.contains("participant=\"accounts@example.com\""));
        assertTrue(wrapped.contains("Please process invoice 42."));
    }
}
