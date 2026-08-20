package sh.vork.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.relay.RelayEncryptionService;
import sh.vork.relay.RelayHttpClient;
import sh.vork.scheduling.service.SystemNotificationService;
import sh.vork.setup.SystemSettingsService;
import sh.vork.surface.Surface;

class ChatServiceSessionBindingTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getOrCreateSession_usesHttpSessionIdAsPersistentKey() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "pw"));

        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                mock(AiOrchestrationService.class),
                mock(SimpMessagingTemplate.class),
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                mock(SystemNotificationService.class),
                Runnable::run,
                mock(RelayEncryptionService.class),
                mock(RelayHttpClient.class),
                mock(SystemSettingsService.class),
                null);

        AiSession created = chatService.getOrCreateSession("http-session-123", AiProvider.GEMINI);
        assertEquals("http-session-123", created.uuid());
        assertEquals("alice", created.username());
        assertEquals(AiSessionStatus.RUNNING, created.status());
        assertEquals(SessionOriginMode.WEB, created.originMode());
        assertEquals(List.of("toggleInputRelay"), created.sessionToolIds());

        AiSession loaded = chatService.getOrCreateSession("http-session-123", AiProvider.GEMINI);
        assertEquals(created.uuid(), loaded.uuid());
        assertEquals(created.createdAt(), loaded.createdAt());
    }

    @Test
    void getOrCreateSession_rejectsCrossUserAccessToExistingSessionId() {
        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        sessionRepo.save(new AiSession(
                "http-session-shared",
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "Untitled",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING, null, null, null, null, null));

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("bob", "pw"));

        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                mock(AiOrchestrationService.class),
                mock(SimpMessagingTemplate.class),
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                mock(SystemNotificationService.class),
                Runnable::run,
                mock(RelayEncryptionService.class),
                mock(RelayHttpClient.class),
                mock(SystemSettingsService.class),
                null);

        assertThrows(IllegalStateException.class,
                () -> chatService.getOrCreateSession("http-session-shared", AiProvider.GEMINI));
    }

    @Test
    void listSessionsForCurrentUser_excludesSurfaceLinkedSessions() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "pw"));

        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);
        MapDatabaseRepository<Surface> surfaceRepo = new MapDatabaseRepository<>(Surface.class);

        AiSession normal = new AiSession(
                "session-normal",
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "General Chat",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING,
                null,
                null,
                null,
                null,
                List.of("toggleInputRelay"));
        AiSession surfaceLinked = new AiSession(
                "session-surface",
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "Surface Session",
                System.currentTimeMillis(),
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING,
                null,
                null,
                null,
                null,
                List.of("toggleInputRelay"));
        sessionRepo.save(normal);
        sessionRepo.save(surfaceLinked);

        surfaceRepo.save(new Surface(
                "surface-1",
                "surfaceone",
                "Surface One",
                "",
                "session-surface",
                "",
                List.of(),
                List.of(),
                List.of(),
                System.currentTimeMillis(),
                System.currentTimeMillis()));

        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                mock(AiOrchestrationService.class),
                mock(SimpMessagingTemplate.class),
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                mock(SystemNotificationService.class),
                Runnable::run,
                mock(RelayEncryptionService.class),
                mock(RelayHttpClient.class),
                mock(SystemSettingsService.class),
                null);

        var field = ChatService.class.getDeclaredField("surfaceRepository");
        field.setAccessible(true);
        field.set(chatService, surfaceRepo);

        List<AiSession> listed = chatService.listSessionsForCurrentUser();

        assertEquals(1, listed.size());
        assertEquals("session-normal", listed.getFirst().uuid());
    }

    @Test
    void listSessionsForCurrentUser_filtersByAgentSearchAndLimit() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "pw"));

        MapDatabaseRepository<AiSession> sessionRepo = new MapDatabaseRepository<>(AiSession.class);

        sessionRepo.save(new AiSession(
                "session-concierge-1",
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "Concierge Alpha",
                System.currentTimeMillis() - 5000,
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING,
                "agent-tpl-concierge-001",
                null,
                null,
                null,
                List.of("toggleInputRelay")));

        sessionRepo.save(new AiSession(
                "session-marketing-1",
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "Marketing Campaign",
                System.currentTimeMillis() - 4000,
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING,
                "agent-marketing-001",
                null,
                null,
                null,
                List.of("toggleInputRelay")));

        sessionRepo.save(new AiSession(
                "session-marketing-2",
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "alice",
                "Marketing Follow-up",
                System.currentTimeMillis() - 3000,
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING,
                "agent-marketing-001",
                null,
                null,
                null,
                List.of("toggleInputRelay")));

        sessionRepo.save(new AiSession(
                "session-other-user",
                AiProvider.GEMINI.name(),
                SessionOriginMode.WEB,
                "bob",
                "Bob Session",
                System.currentTimeMillis() - 2000,
                0,
                List.of(),
                AiSession.defaultEnvironmentVariables(),
                AiSessionStatus.RUNNING,
                "agent-marketing-001",
                null,
                null,
                null,
                List.of("toggleInputRelay")));

        ChatService chatService = new ChatService(
                sessionRepo,
                null,
                mock(AiOrchestrationService.class),
                mock(SimpMessagingTemplate.class),
                new ObjectMapper().findAndRegisterModules(),
                List.of(),
                mock(SystemNotificationService.class),
                Runnable::run,
                mock(RelayEncryptionService.class),
                mock(RelayHttpClient.class),
                mock(SystemSettingsService.class),
                null);

        List<AiSession> marketingOnly = chatService.listSessionsForCurrentUser("agent-marketing-001", null, 200);
        assertEquals(2, marketingOnly.size());

        List<AiSession> searched = chatService.listSessionsForCurrentUser("agent-marketing-001", "follow", 200);
        assertEquals(1, searched.size());
        assertEquals("session-marketing-2", searched.getFirst().uuid());

        List<AiSession> limited = chatService.listSessionsForCurrentUser("agent-marketing-001", null, 1);
        assertEquals(1, limited.size());
    }
}
