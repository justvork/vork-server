package sh.vork.ai.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class PendingSessionsControllerTest {

    @Test
    void listPendingInputSessions_returnsGone() {
        PendingSessionsController controller = new PendingSessionsController();

        ResponseEntity<Map<String, Object>> response = controller.pendingInputSessions();

        assertEquals(410, response.getStatusCode().value());
        assertEquals("GONE", response.getBody().get("status"));
    }

    @Test
    void dismissPendingInputSession_returnsGone() {
        PendingSessionsController controller = new PendingSessionsController();

        ResponseEntity<Map<String, Object>> response = controller.dismissPendingInputSession("session-1");

        assertEquals(410, response.getStatusCode().value());
        assertEquals("GONE", response.getBody().get("status"));
    }
}
