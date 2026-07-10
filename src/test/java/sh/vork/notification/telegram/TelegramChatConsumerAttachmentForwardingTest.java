package sh.vork.notification.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiChatMessage.AttachmentRef;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.service.ChatService;
import sh.vork.ai.telegram.TelegramChatResumptionService;
import sh.vork.ai.telegram.TelegramSessionRegistry;
import sh.vork.ai.telegram.TelegramSuspensionRenderer;
import sh.vork.filesystem.FileDescriptor;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.notification.user.UserNotificationMedia;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.transcription.AudioTranscriptionService;

import java.io.ByteArrayInputStream;
import java.util.List;
import org.mockito.ArgumentCaptor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TelegramChatConsumerAttachmentForwardingTest {

    @SuppressWarnings("unchecked")
    @Test
    void process_uploadsAiSessionAttachmentAsTelegramDocument() throws Exception {
        ChatService chatService = mock(ChatService.class);
        DatabaseRepository<AiSession> sessionRepo = (DatabaseRepository<AiSession>) mock(DatabaseRepository.class);
        MapDatabaseRepository<UserNotificationMedia> mediaRepo = new MapDatabaseRepository<>(UserNotificationMedia.class);
        TelegramSessionRegistry sessionRegistry = mock(TelegramSessionRegistry.class);
        TelegramChatResumptionService resumptionService = mock(TelegramChatResumptionService.class);
        TelegramSuspensionRenderer suspensionRenderer = mock(TelegramSuspensionRenderer.class);
        TelegramApiClient telegramApiClient = mock(TelegramApiClient.class);
        AudioTranscriptionService audioTranscriptionService = mock(AudioTranscriptionService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);

        TelegramChatConsumer consumer = new TelegramChatConsumer(
                chatService,
                sessionRepo,
                mediaRepo,
                sessionRegistry,
                resumptionService,
                suspensionRenderer,
                telegramApiClient,
                new ObjectMapper(),
                audioTranscriptionService,
                sessionFileSystem);

        mediaRepo.save(new UserNotificationMedia(
                "m1", "alice", "telegram", sh.vork.notification.NotificationMediaType.TELEGRAM,
                "12345", "Telegram", true, true, System.currentTimeMillis()));

        when(sessionRegistry.getOrCreate(eq("alice"), eq("cfg-1"), eq("12345"), eq("bot-token")))
                .thenReturn("sess-1");

        when(sessionFileSystem.read(eq(FileArea.SESSION), eq("sess-1"), eq("reports/summary.pdf")))
                .thenReturn(new ByteArrayInputStream("PDF".getBytes()));

        AiChatMessage response = new AiChatMessage(
                "msg-1",
                "ASSISTANT",
                "Done.",
                System.currentTimeMillis(),
                List.of(new AttachmentRef(
                        "/api/session-files/download?area=SESSION&sessionUuid=sess-1&path=reports%2Fsummary.pdf",
                        "summary.pdf",
                        "application/pdf",
                        "/api/session-files/download?area=SESSION&sessionUuid=sess-1&path=reports%2Fsummary.pdf")),
                null,
                null,
                null);
        when(chatService.sendMessageAsUser(eq("alice"), eq("sess-1"), eq("pdf"), any(), any()))
                .thenReturn(response);

        TelegramMessageConsumer.IncomingMessage incoming = new TelegramMessageConsumer.IncomingMessage(
                "cfg-1", "bot-token", "12345", "", "private", "Alice", "alice",
                "pdf", 1, null, null, null, null, null, null, null);

        consumer.process(incoming);

        verify(telegramApiClient).sendDocument(
                eq("bot-token"),
                eq("12345"),
                eq("summary.pdf"),
                eq("application/pdf"),
                any(byte[].class),
                eq(null));
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_withInboundTelegramFile_passesSessionUrlAttachmentToChat() throws Exception {
        ChatService chatService = mock(ChatService.class);
        DatabaseRepository<AiSession> sessionRepo = (DatabaseRepository<AiSession>) mock(DatabaseRepository.class);
        MapDatabaseRepository<UserNotificationMedia> mediaRepo = new MapDatabaseRepository<>(UserNotificationMedia.class);
        TelegramSessionRegistry sessionRegistry = mock(TelegramSessionRegistry.class);
        TelegramChatResumptionService resumptionService = mock(TelegramChatResumptionService.class);
        TelegramSuspensionRenderer suspensionRenderer = mock(TelegramSuspensionRenderer.class);
        TelegramApiClient telegramApiClient = mock(TelegramApiClient.class);
        AudioTranscriptionService audioTranscriptionService = mock(AudioTranscriptionService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);

        TelegramChatConsumer consumer = new TelegramChatConsumer(
                chatService,
                sessionRepo,
                mediaRepo,
                sessionRegistry,
                resumptionService,
                suspensionRenderer,
                telegramApiClient,
                new ObjectMapper(),
                audioTranscriptionService,
                sessionFileSystem);

        mediaRepo.save(new UserNotificationMedia(
                "m1", "alice", "telegram", sh.vork.notification.NotificationMediaType.TELEGRAM,
                "12345", "Telegram", true, true, System.currentTimeMillis()));

        when(sessionRegistry.getOrCreate(eq("alice"), eq("cfg-1"), eq("12345"), eq("bot-token")))
                .thenReturn("sess-1");

        when(telegramApiClient.downloadFile(eq("bot-token"), eq("file-123")))
                .thenReturn("ABC".getBytes());
        when(sessionFileSystem.write(eq(FileArea.SESSION), eq("sess-1"), anyString(), any(ByteArrayInputStream.class), eq(3L)))
                .thenReturn(new FileDescriptor(
                        FileArea.SESSION,
                        "sess-1",
                        "incoming/telegram/test.txt",
                        3,
                        "/api/session-files/download?area=SESSION&sessionUuid=sess-1&path=incoming%2Ftelegram%2Ftest.txt"));

        when(chatService.sendMessageAsUser(eq("alice"), eq("sess-1"), eq("Please analyze the attached file."), any(), any()))
                .thenReturn(new AiChatMessage("m", "ASSISTANT", "ok", System.currentTimeMillis(), null, null, null, null));

        TelegramMessageConsumer.IncomingMessage incoming = new TelegramMessageConsumer.IncomingMessage(
                "cfg-1", "bot-token", "12345", "", "private", "Alice", "alice",
                "", 1, null, null, null, null, "file-123", "text/plain", "test.txt");

        consumer.process(incoming);

        ArgumentCaptor<List<String>> attachmentCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatService).sendMessageAsUser(eq("alice"), eq("sess-1"), eq("Please analyze the attached file."), attachmentCaptor.capture(), any());
        List<String> refs = attachmentCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(1, refs.size());
        org.junit.jupiter.api.Assertions.assertTrue(refs.get(0).startsWith("session-url:/api/session-files/download?"));
    }
}
