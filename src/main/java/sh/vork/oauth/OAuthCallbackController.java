package sh.vork.oauth;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import sh.vork.ai.entity.AiSession;
import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.orm.DatabaseRepository;

@RestController
@RequestMapping("/api/oauth")
public class OAuthCallbackController {

    private static final Logger log = LoggerFactory.getLogger(OAuthCallbackController.class);

    private final OAuthClientService oauthClientService;
    private final DatabaseRepository<AiSession> sessionRepository;

    public OAuthCallbackController(OAuthClientService oauthClientService,
                                   DatabaseRepository<AiSession> sessionRepository) {
        this.oauthClientService = oauthClientService;
        this.sessionRepository = sessionRepository;
    }

    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam("state") String state,
                                           @RequestParam(value = "code", required = false) String code,
                                           @RequestParam(value = "error", required = false) String error) {
        Map<String, Object> result = oauthClientService.completeCallback(state, code, error);
        if ("ok".equals(result.get("status"))) {
            String sessionUuid = String.valueOf(result.getOrDefault("sessionUuid", ""));
            SessionOriginMode originMode = resolveOriginMode(sessionUuid);
            String autoResumeScript = "";
            String followUpMessage;
            
            log.debug("OAuth callback successful [sessionUuid={}, originMode={}]", 
                      sessionUuid.isBlank() ? "empty" : sessionUuid, originMode);
            
            if (!sessionUuid.isBlank()) {
                autoResumeScript = """
                    <script>
                    (async function () {
                        try {
                            const response = await fetch('/api/chat/authorize/%s?approved=true&policy=ONCE', { method: 'GET', credentials: 'same-origin' });
                            if (!response.ok) {
                                console.error('Authorization failed:', response.status);
                            } else {
                                console.log('Authorization successful');
                            }
                        } catch (e) {
                            console.error('Authorization error:', e.message);
                        }
                        // Always redirect as authorization was processed
                        %s
                    }());
                    </script>
                    """.formatted(sessionUuid,
                        originMode == SessionOriginMode.WEB
                                ? "window.location.href = '/index.html';"
                                : "");
            } else {
                // Fallback redirect if sessionUuid is empty (non-web origins or background OAuth)
                log.debug("OAuth callback: No session UUID available, using fallback redirect for origin={}", originMode);
                if (originMode == SessionOriginMode.WEB) {
                    autoResumeScript = """
                        <script>
                        // Fallback redirect after short delay when session context is unavailable
                        console.log('OAuth callback: redirecting via fallback (no session context)');
                        setTimeout(function() { window.location.href = '/index.html'; }, 1000);
                        </script>
                        """;
                }
            }

            if (originMode == SessionOriginMode.WEB) {
                followUpMessage = "Returning you to chat…";
            } else if (originMode == SessionOriginMode.TELEGRAM) {
                followUpMessage = "OAuth connected. You can return to Telegram and continue there.";
            } else if (originMode == SessionOriginMode.SLACK) {
                followUpMessage = "OAuth connected. You can return to Slack and continue there.";
            } else {
                followUpMessage = "OAuth connected. You can return to your original channel and continue.";
            }
            return ResponseEntity.ok("""
                    <html><body>
                    <h3>OAuth connection completed</h3>
                    <p>%s</p>
                    %s
                    </body></html>
                    """.formatted(followUpMessage, autoResumeScript));
        }
        String message = String.valueOf(result.getOrDefault("message", "OAuth callback failed"));
        log.warn("OAuth callback failed: {}", message);
        return ResponseEntity.badRequest().body("""
                <html><body>
                <h3>OAuth connection failed</h3>
                <p>%s</p>
                </body></html>
                """.formatted(message));
    }

    private SessionOriginMode resolveOriginMode(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return SessionOriginMode.WEB;
        }
        AiSession session = sessionRepository.get(sessionUuid);
        if (session == null || session.originMode() == null) {
            return SessionOriginMode.WEB;
        }
        return session.originMode();
    }
}
