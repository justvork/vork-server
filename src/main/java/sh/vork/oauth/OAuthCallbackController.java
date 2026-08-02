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
import sh.vork.reflection.ReflectionService;

@RestController
@RequestMapping("/api/oauth")
public class OAuthCallbackController {

    private static final Logger log = LoggerFactory.getLogger(OAuthCallbackController.class);

    private final OAuthClientService oauthClientService;
    private final DatabaseRepository<AiSession> sessionRepository;
    private final ReflectionService reflectionService;

    public OAuthCallbackController(OAuthClientService oauthClientService,
                                   DatabaseRepository<AiSession> sessionRepository,
                                   ReflectionService reflectionService) {
        this.oauthClientService = oauthClientService;
        this.sessionRepository = sessionRepository;
        this.reflectionService = reflectionService;
    }

    @GetMapping(value = "/callback", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> callback(@RequestParam("state") String state,
                                           @RequestParam(value = "code", required = false) String code,
                                           @RequestParam(value = "error", required = false) String error) {
        Map<String, Object> result = oauthClientService.completeCallback(state, code, error);
        if ("ok".equals(result.get("status"))) {
            ReflectionService.PendingOAuthBindingCompletion pendingBindingCompletion =
                    reflectionService.completePendingOAuthBinding(state);

            String sessionUuid = String.valueOf(result.getOrDefault("sessionUuid", ""));
            SessionOriginMode originMode = resolveOriginMode(sessionUuid);
            String returnPath = sanitizeReturnPath((String) result.get("returnPath"));
            String webRedirectTarget = returnPath != null ? returnPath : "/index.html";
            if (pendingBindingCompletion.handled()) {
                if (pendingBindingCompletion.success()) {
                    webRedirectTarget = appendQueryParam(webRedirectTarget, "oauthBindingStatus", "created");
                    if (pendingBindingCompletion.bindingName() != null && !pendingBindingCompletion.bindingName().isBlank()) {
                        webRedirectTarget = appendQueryParam(webRedirectTarget, "oauthBindingName", pendingBindingCompletion.bindingName());
                    }
                } else {
                    webRedirectTarget = appendQueryParam(webRedirectTarget, "oauthBindingStatus", "error");
                    webRedirectTarget = appendQueryParam(
                            webRedirectTarget,
                            "oauthBindingMessage",
                            pendingBindingCompletion.message() == null ? "Failed to save binding after OAuth callback." : pendingBindingCompletion.message());
                }
            }
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
                                ? "window.location.href = '" + jsStringLiteral(webRedirectTarget) + "';"
                                : "");
            } else {
                // Fallback redirect if sessionUuid is empty (non-web origins or background OAuth)
                log.debug("OAuth callback: No session UUID available, using fallback redirect for origin={}", originMode);
                if (originMode == SessionOriginMode.WEB) {
                    autoResumeScript = """
                        <script>
                        // Fallback redirect after short delay when session context is unavailable
                        console.log('OAuth callback: redirecting via fallback (no session context)');
                        setTimeout(function() { window.location.href = '%s'; }, 1000);
                        </script>
                        """.formatted(jsStringLiteral(webRedirectTarget));
                }
            }

            if (originMode == SessionOriginMode.WEB) {
                if (pendingBindingCompletion.handled() && pendingBindingCompletion.success()) {
                    followUpMessage = "OAuth connected and binding saved. Returning you to reflections…";
                } else if (pendingBindingCompletion.handled()) {
                    followUpMessage = "OAuth connected, but binding save failed. Returning you to reflections…";
                } else {
                    followUpMessage = "Returning you to chat…";
                }
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

    private static String sanitizeReturnPath(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isBlank()) {
            return null;
        }
        if (!value.startsWith("/")) {
            return null;
        }
        if (value.startsWith("//") || value.contains("://")) {
            return null;
        }
        return value;
    }

    private static String jsStringLiteral(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String appendQueryParam(String path, String key, String value) {
        if (path == null || path.isBlank()) {
            return path;
        }
        String separator = path.contains("?") ? "&" : "?";
        String encoded = java.net.URLEncoder.encode(value == null ? "" : value, java.nio.charset.StandardCharsets.UTF_8);
        return path + separator + key + "=" + encoded;
    }
}
