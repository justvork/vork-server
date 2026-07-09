package sh.vork.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import sh.vork.ai.AiProvider;
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
import sh.vork.storage.FileStorageService;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceSessionAttachmentRawUrlTest {

    @Test
    void sendMessageAsUser_acceptsRawSessionDownloadUrlAttachmentId() throws Exception {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        AiOrchestrationService aiService = mock(AiOrchestrationService.class);
        FileStorageService fileStorageService = mock(FileStorageService.class);
        SessionFileSystem sessionFileSystem = mock(SessionFileSystem.class);

        String sessionUuid = "session-attachment-raw-url";
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

        String rawUrl = "/api/session-files/download?area=SESSION&sessionUuid=" + sessionUuid + "&path=images%2Fphoto.png";
        when(sessionFileSystem.read(eq(FileArea.SESSION), eq(sessionUuid), eq("images/photo.png")))
                .thenReturn(new ByteArrayInputStream("img-bytes".getBytes(StandardCharsets.UTF_8)));

        when(aiService.generateWithHistoryAndMedia(anyList(), any(String.class), anyList(), eq(AiProvider.GEMINI)))
                .thenReturn("{\"status\":\"FINISHED_TURN\",\"textResponse\":\"ok\"}");

        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                aiService,
                fileStorageService,
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

        chatService.sendMessageAsUser("alice", sessionUuid, "describe image", List.of(rawUrl), AiProvider.GEMINI);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiService).generateWithHistoryAndMedia(anyList(), promptCaptor.capture(), anyList(), eq(AiProvider.GEMINI));
        assertTrue(promptCaptor.getValue().contains("describe image"));
    }
}
