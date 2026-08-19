package sh.vork.attention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Duration;
import java.util.Map;

@Controller
@RequestMapping
public class AttentionController {

    private static final Logger log = LoggerFactory.getLogger(AttentionController.class);

    private final AttentionAlertService attentionAlertService;

    public AttentionController(AttentionAlertService attentionAlertService) {
        this.attentionAlertService = attentionAlertService;
    }

    @GetMapping("/attention")
    @PreAuthorize("isAuthenticated()")
    public String attentionPage() {
        return "attention";
    }

    @GetMapping("/api/attention/alerts")
    @PreAuthorize("isAuthenticated()")
    @ResponseBody
    public ResponseEntity<?> listDueAlerts() {
        String channelName = resolveChannelName();
        return ResponseEntity.ok(attentionAlertService.listDueAlertsForChannel(channelName));
    }

    @GetMapping("/api/attention/count")
    @PreAuthorize("isAuthenticated()")
    @ResponseBody
    public ResponseEntity<?> countDueAlerts() {
        String channelName = resolveChannelName();
        long count = attentionAlertService.countDueAlertsForChannel(channelName);
        return ResponseEntity.ok(Map.of("count", count));
    }

    @PostMapping("/api/attention/alerts")
    @PreAuthorize("isAuthenticated()")
    @ResponseBody
    public ResponseEntity<?> createAlert(@RequestBody CreateAttentionAlertRequest request) {
        try {
            AttentionAlert created = attentionAlertService.create(
                    new AttentionAlertService.CreateAttentionAlertCommand(
                            request.channelNames(),
                            request.alertName(),
                            request.description(),
                            request.resolutionPolicy(),
                            request.actionUrl(),
                            request.attentionAt() == null ? 0L : request.attentionAt(),
                            request.sourceType(),
                            request.sourceId()));
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @DeleteMapping("/api/attention/alerts/{alertUuid}")
    @PreAuthorize("isAuthenticated()")
    @ResponseBody
    public ResponseEntity<?> dismissAlert(@PathVariable String alertUuid) {
        String channelName = resolveChannelName();
        try {
            attentionAlertService.dismiss(channelName, alertUuid);
            return ResponseEntity.ok(Map.of("status", "DISMISSED", "alertUuid", alertUuid));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(Map.of("status", "NOT_DISMISSABLE", "message", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    @PostMapping("/api/attention/alerts/{alertUuid}/remind")
    @PreAuthorize("isAuthenticated()")
    @ResponseBody
    public ResponseEntity<?> remindAlert(@PathVariable String alertUuid,
                                         @RequestBody RemindAttentionRequest request) {
        String channelName = resolveChannelName();
        try {
            long targetAt = resolveAttentionAt(request);
            AttentionAlert updated = attentionAlertService.remind(channelName, alertUuid, targetAt);
            return ResponseEntity.ok(Map.of(
                    "status", "REMIND_SET",
                    "alertUuid", updated.uuid(),
                    "attentionAt", updated.attentionAt()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }

    private static String resolveChannelName() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            return "anonymous";
        }
        return auth.getName();
    }

    private static long resolveAttentionAt(RemindAttentionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Reminder request is required.");
        }

        if (request.attentionAt() != null && request.attentionAt() > 0) {
            return request.attentionAt();
        }

        String preset = request.preset() == null ? "" : request.preset().trim().toUpperCase();
        long now = System.currentTimeMillis();
        return switch (preset) {
            case "TOMORROW" -> now + Duration.ofDays(1).toMillis();
            case "IN_7_DAYS" -> now + Duration.ofDays(7).toMillis();
            case "IN_30_DAYS" -> now + Duration.ofDays(30).toMillis();
            default -> throw new IllegalArgumentException("Either attentionAt or a valid preset is required.");
        };
    }

    public record RemindAttentionRequest(String preset, Long attentionAt) {
    }
}
