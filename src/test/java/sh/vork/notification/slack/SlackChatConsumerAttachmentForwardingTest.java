package sh.vork.notification.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiChatMessage.AttachmentRef;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.service.ChatService;
import sh.vork.ai.slack.SlackSessionRegistry;
import sh.vork.ai.slack.SlackSuspensionRenderer;
import sh.vork.ai.telegram.TelegramChatResumptionService;
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

class SlackChatConsumerAttachmentForwardingTest {

    @SuppressWarnings("unchecked")
    @Test
    void process_uploadsAiSessionAttachmentAsSlackFile() throws Exception {
        ChatService chatService = mock(ChatService.class);
        DatabaseRepository<AiSession> sessionRepo = (DatabaseRepository<AiSession>) mock(DatabaseRepository.class);
        MapDatabaseRepository<UserNotificationMedia> mediaRepo = new MapDatabaseRepository<>(UserNotificationMedia.class);
        SlackSessionRegistry sessionRegistry = mock(SlackSessionRegistry.class);
        TelegramChatResumptionService resumptionService = mock(TelegramChatResumptionService.class);
        SlackSuspensionRenderer suspensionRenderer = mock(SlackSuspensionRenderer.class);
        SlackApiClient slackApiClient = mock(SlackApiClient.class);
        AudioTranscriptionService audioTranscriptionService = mock(AudioTranscriptionService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);

        SlackChatConsumer consumer = new SlackChatConsumer(
                chatService,
                sessionRepo,
                mediaRepo,
                sessionRegistry,
                resumptionService,
                suspensionRenderer,
                slackApiClient,
                new ObjectMapper(),
                audioTranscriptionService,
                sessionFileSystem);

        mediaRepo.save(new UserNotificationMedia(
                "m1", "alice", "slack", sh.vork.notification.NotificationMediaType.SLACK,
                "U123", "Slack", true, true, System.currentTimeMillis()));

        when(sessionRegistry.getOrCreate(eq("alice"), eq("cfg-1"), eq("D123"), eq("xoxb-token")))
                .thenReturn("sess-1");

        when(sessionFileSystem.read(eq(FileArea.SESSION), eq("sess-1"), eq("exports/docs.zip")))
                .thenReturn(new ByteArrayInputStream("ZIP".getBytes()));

        AiChatMessage response = new AiChatMessage(
                "msg-1",
                "ASSISTANT",
                "Done.",
                System.currentTimeMillis(),
                List.of(new AttachmentRef(
                        "/api/session-files/download?area=SESSION&sessionUuid=sess-1&path=exports%2Fdocs.zip",
                        "docs.zip",
                        "application/zip",
                        "/api/session-files/download?area=SESSION&sessionUuid=sess-1&path=exports%2Fdocs.zip")),
                null,
                null,
                null);
        when(chatService.sendMessageAsUser(eq("alice"), eq("sess-1"), eq("zip"), any(), any()))
                .thenReturn(response);

        SlackMessageConsumer.IncomingSlackMessage incoming = new SlackMessageConsumer.IncomingSlackMessage(
                "cfg-1", "xoxb-token", "D123", "im", "U123", "zip", "1700000",
                null, null, null, null, null);

        consumer.process(incoming);

        verify(slackApiClient).sendFile(
                eq("xoxb-token"),
                eq("D123"),
                eq("docs.zip"),
                eq("application/zip"),
                any(byte[].class),
                eq(null));
    }

    @SuppressWarnings("unchecked")
    @Test
    void process_withInboundSlackFile_passesSessionUrlAttachmentToChat() throws Exception {
        ChatService chatService = mock(ChatService.class);
        DatabaseRepository<AiSession> sessionRepo = (DatabaseRepository<AiSession>) mock(DatabaseRepository.class);
        MapDatabaseRepository<UserNotificationMedia> mediaRepo = new MapDatabaseRepository<>(UserNotificationMedia.class);
        SlackSessionRegistry sessionRegistry = mock(SlackSessionRegistry.class);
        TelegramChatResumptionService resumptionService = mock(TelegramChatResumptionService.class);
        SlackSuspensionRenderer suspensionRenderer = mock(SlackSuspensionRenderer.class);
        SlackApiClient slackApiClient = mock(SlackApiClient.class);
        AudioTranscriptionService audioTranscriptionService = mock(AudioTranscriptionService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);

        SlackChatConsumer consumer = new SlackChatConsumer(
                chatService,
                sessionRepo,
                mediaRepo,
                sessionRegistry,
                resumptionService,
                suspensionRenderer,
                slackApiClient,
                new ObjectMapper(),
                audioTranscriptionService,
                sessionFileSystem);

        mediaRepo.save(new UserNotificationMedia(
                "m1", "alice", "slack", sh.vork.notification.NotificationMediaType.SLACK,
                "U123", "Slack", true, true, System.currentTimeMillis()));

        when(sessionRegistry.getOrCreate(eq("alice"), eq("cfg-1"), eq("D123"), eq("xoxb-token")))
                .thenReturn("sess-1");

        when(slackApiClient.downloadFile(eq("xoxb-token"), eq("https://slack.example/file")))
                .thenReturn("ABC".getBytes());
        when(sessionFileSystem.write(eq(FileArea.SESSION), eq("sess-1"), anyString(), any(ByteArrayInputStream.class), eq(3L)))
                .thenReturn(new FileDescriptor(
                        FileArea.SESSION,
                        "sess-1",
                        "incoming/slack/test.txt",
                        3,
                        "/api/session-files/download?area=SESSION&sessionUuid=sess-1&path=incoming%2Fslack%2Ftest.txt"));

        when(chatService.sendMessageAsUser(eq("alice"), eq("sess-1"), eq("Please analyze the attached file."), any(), any()))
                .thenReturn(new AiChatMessage("m", "ASSISTANT", "ok", System.currentTimeMillis(), null, null, null, null));

        SlackMessageConsumer.IncomingSlackMessage incoming = new SlackMessageConsumer.IncomingSlackMessage(
                "cfg-1", "xoxb-token", "D123", "im", "U123", "", "1700000",
                null, null, "https://slack.example/file", "text/plain", "test.txt");

        consumer.process(incoming);

        ArgumentCaptor<List<String>> attachmentCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatService).sendMessageAsUser(eq("alice"), eq("sess-1"), eq("Please analyze the attached file."), attachmentCaptor.capture(), any());
        List<String> refs = attachmentCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(1, refs.size());
        org.junit.jupiter.api.Assertions.assertTrue(refs.get(0).startsWith("session-url:/api/session-files/download?"));
    }
}
