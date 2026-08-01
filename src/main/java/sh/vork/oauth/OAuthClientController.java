package sh.vork.oauth;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/oauth-clients")
public class OAuthClientController {

    private static final Logger log = LoggerFactory.getLogger(OAuthClientController.class);

    private final OAuthClientService oauthClientService;

    public OAuthClientController(OAuthClientService oauthClientService) {
        this.oauthClientService = oauthClientService;
    }

    @PostMapping("/{clientUuid}/duplicate-profile")
    public ResponseEntity<?> duplicateProfile(@PathVariable String clientUuid,
                                              @RequestBody DuplicateProfileRequest request) {
        log.debug("ENTER duplicateProfile: clientUuid={}", clientUuid);
        String username = resolveUsername();
        if (username == null) {
            log.warn("duplicateProfile rejected: unauthenticated request [clientUuid={}]", clientUuid);
            return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Authenticated user is required"));
        }

        Map<String, Object> result = oauthClientService.duplicateProfileAndConnect(
                username,
                clientUuid,
                request == null ? null : request.profileName(),
                request == null ? null : request.returnPath());

        String status = String.valueOf(result.getOrDefault("status", ""));
        if (!"error".equals(status)) {
            log.debug("EXIT duplicateProfile: status={}", status);
            return ResponseEntity.ok(result);
        }

        String message = String.valueOf(result.getOrDefault("message", "OAuth profile duplication failed"));
        if ("Source OAuth client was not found".equals(message)) {
            log.warn("duplicateProfile failed: source client not found [user={}, clientUuid={}]", username, clientUuid);
            return ResponseEntity.status(404).body(result);
        }
        log.warn("duplicateProfile failed [user={}, clientUuid={}]: {}", username, clientUuid, message);
        return ResponseEntity.badRequest().body(result);
    }

    public record DuplicateProfileRequest(String profileName, String returnPath) {}

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(auth.getName())) {
            return null;
        }
        return auth.getName();
    }
}
