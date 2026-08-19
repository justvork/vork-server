package sh.vork.ai.controller;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.HttpStatus;

/**
 * Legacy controller retained only to return explicit deprecation responses for
 * retired pending-input routes.
 */
@Controller
public class PendingSessionsController {

    private static final Logger log = LoggerFactory.getLogger(PendingSessionsController.class);

    public PendingSessionsController() {
    }

    // ── Page ──────────────────────────────────────────────────────────────────

    @GetMapping("/pending-sessions")
    public String pendingSessionsPage() {
        log.debug("ENTER pendingSessionsPage");
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Page not found");
    }

    // ── REST: retired pending-input routes ────────────────────────────────────

    @GetMapping("/api/chat/sessions/pending-input")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> pendingInputSessions() {
        log.debug("pendingInputSessions endpoint called after retirement");
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "status", "GONE",
                "message", "Endpoint retired. Use /api/attention/alerts instead."));
    }

    @DeleteMapping("/api/chat/sessions/pending-input/{sessionUuid}")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> dismissPendingInputSession(@PathVariable String sessionUuid) {
        log.debug("dismissPendingInputSession endpoint called after retirement [session={}]", sessionUuid);
        return ResponseEntity.status(HttpStatus.GONE).body(Map.of(
                "status", "GONE",
                "message", "Endpoint retired. Use attention actions in /attention instead."));
    }
}
