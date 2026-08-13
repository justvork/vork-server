package sh.vork.github.auth;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/github/device-flow")
@PreAuthorize("isAuthenticated()")
public class GitHubDeviceFlowController {

    private static final Logger log = LoggerFactory.getLogger(GitHubDeviceFlowController.class);

    private final GitHubDeviceFlowService deviceFlowService;

    public GitHubDeviceFlowController(GitHubDeviceFlowService deviceFlowService) {
        this.deviceFlowService = deviceFlowService;
    }

    @PostMapping({"/start", "/start/"})
    public ResponseEntity<?> start(@RequestBody(required = false) StartRequest request) {
        String username = resolveUsername();
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Authenticated user is required"));
        }

        Map<String, Object> result = deviceFlowService.start(username, request == null ? null : request.scope());
        if ("error".equals(result.get("status"))) {
            log.warn("GitHub Device Flow start failed [username={}]: {}", username, result.get("message"));
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping({"/{flowId}/poll", "/{flowId}/poll/"})
    public ResponseEntity<?> poll(@PathVariable String flowId) {
        String username = resolveUsername();
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Authenticated user is required"));
        }

        Map<String, Object> result = deviceFlowService.poll(username, flowId);
        if ("error".equals(result.get("status"))) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        String username = resolveUsername();
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Authenticated user is required"));
        }
        return ResponseEntity.ok(deviceFlowService.status(username));
    }

    @DeleteMapping("/status")
    public ResponseEntity<?> disconnect() {
        String username = resolveUsername();
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("status", "error", "message", "Authenticated user is required"));
        }
        return ResponseEntity.ok(deviceFlowService.disconnect(username));
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(auth.getName())) {
            return null;
        }
        return auth.getName();
    }

    public record StartRequest(String scope) {
    }
}
