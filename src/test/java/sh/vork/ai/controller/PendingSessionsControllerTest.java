package sh.vork.ai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.AiSessionStatus;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.orm.mock.MapDatabaseRepository;

class PendingSessionsControllerTest {

    private final MapDatabaseRepository<AiSession> sessionRepo =
            new MapDatabaseRepository<>(AiSession.class);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void dismissPendingInputSession_marksSessionCompletedAndAppendsAuditMessage() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", "n/a"));

        AiSession pending = pendingSession("session-1", "alice", AiSessionStatus.AWAITING_INPUT);
        sessionRepo.save(pending);

        PendingSessionsController controller = new PendingSessionsController(sessionRepo, new ObjectMapper());

        ResponseEntity<Map<String, Object>> response = controller.dismissPendingInputSession("session-1");

        assertEquals(200, response.getStatusCode().value());
        assertEquals("DISMISSED", response.getBody().get("status"));

        AiSession updated = sessionRepo.get("session-1");
        assertNotNull(updated);
        assertEquals(AiSessionStatus.COMPLETED, updated.status());
        assertEquals(2, updated.messages().size());
        assertEquals("ASSISTANT", updated.messages().get(1).role());
        assertEquals("Pending input request dismissed by user.", updated.messages().get(1).content());
    }

    @Test
    void dismissPendingInputSession_returnsForbiddenForDifferentUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("bob", "n/a"));

        AiSession pending = pendingSession("session-2", "alice", AiSessionStatus.AWAITING_INPUT);
        sessionRepo.save(pending);

        PendingSessionsController controller = new PendingSessionsController(sessionRepo, new ObjectMapper());

        ResponseEntity<Map<String, Object>> response = controller.dismissPendingInputSession("session-2");

        assertEquals(403, response.getStatusCode().value());
        assertEquals("FORBIDDEN", response.getBody().get("status"));
        assertEquals(AiSessionStatus.AWAITING_INPUT, sessionRepo.get("session-2").status());
    }

    private static AiSession pendingSession(String uuid, String username, AiSessionStatus status) {
        AiChatMessage prompt = new AiChatMessage(
                "msg-1",
                "PROMPT_REQUIRED",
                "{\"eventId\":\"evt-1\",\"eventType\":\"PROMPT_REQUIRED\",\"responseType\":\"AUTHORIZE_TOOL\",\"textResponse\":\"Need approval\",\"formSchema\":null}",
                System.currentTimeMillis(),
                null,
                null,
                "pending-1",
                "sendNotification");

        return new AiSession(
                uuid,
                "GEMINI",
                SessionOriginMode.BACKGROUND,
                username,
                "Pending Session",
                System.currentTimeMillis(),
                0,
                List.of(prompt),
                AiSession.defaultEnvironmentVariables(),
                status,
                null,
                null,
                List.of(),
                List.of(),
                List.of());
    }
}
