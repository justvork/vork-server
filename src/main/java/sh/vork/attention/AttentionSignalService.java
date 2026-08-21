package sh.vork.attention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import sh.vork.channel.ChannelService;
import sh.vork.mcp.model.McpBindingStatus;
import sh.vork.orm.DatabaseRepository;
import sh.vork.security.VorkUser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class AttentionSignalService {

    private static final Logger log = LoggerFactory.getLogger(AttentionSignalService.class);

    private final AttentionAlertService attentionAlertService;
    private final DatabaseRepository<VorkUser> userRepository;

    public AttentionSignalService(AttentionAlertService attentionAlertService,
                                  DatabaseRepository<VorkUser> userRepository) {
        this.attentionAlertService = attentionAlertService;
        this.userRepository = userRepository;
    }

    public void onSessionSuspended(String sessionUuid, String username, String toolName, String reason) {
        if (sessionUuid == null || sessionUuid.isBlank() || username == null || username.isBlank()) {
            return;
        }

        String normalizedUser = ChannelService.normalize(username);
        if (normalizedUser.isBlank()) {
            return;
        }

        // Session suspension no longer creates per-requestor attention alerts.
        // The prompt-required UX and channel notifications remain the primary signal.
        attentionAlertService.resolveBySource(AttentionSourceType.SESSION_SUSPENSION, sessionUuid);
        log.debug("Session suspension alert creation skipped [session={}, channel={}]", sessionUuid, normalizedUser);
    }

    public void onSessionResumed(String sessionUuid) {
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return;
        }
        int removed = attentionAlertService.resolveBySource(AttentionSourceType.SESSION_SUSPENSION, sessionUuid);
        if (removed > 0) {
            log.info("Session suspension alert resolved [session={}, removed={}]", sessionUuid, removed);
        }
    }

    public void onMcpStatusChanged(String bindingUuid,
                                   String bindingName,
                                   McpBindingStatus previousStatus,
                                   McpBindingStatus newStatus,
                                   String details) {
        if (bindingUuid == null || bindingUuid.isBlank() || newStatus == null) {
            return;
        }

        boolean previousProblem = isProblemStatus(previousStatus);
        boolean currentProblem = isProblemStatus(newStatus);

        if (!previousProblem && !currentProblem) {
            return;
        }

        String sourceId = "mcp-binding:" + bindingUuid;

        if (!currentProblem) {
            int removed = attentionAlertService.resolveBySource(AttentionSourceType.MCP_STATUS_CHANGE, sourceId);
            if (removed > 0) {
                log.info("MCP status alert resolved [binding={}, removed={}, status={}]",
                        bindingUuid, removed, newStatus);
            }
            return;
        }

        if (previousStatus == newStatus) {
            return;
        }

        List<String> channels = resolveAdminChannels();
        if (channels.isEmpty()) {
            log.warn("Skipping MCP status alert because no admin channels were found [binding={}]", bindingUuid);
            return;
        }

        String safeBindingName = (bindingName == null || bindingName.isBlank()) ? bindingUuid : bindingName;
        String statusLabel = newStatus.name().toLowerCase(Locale.ROOT);
        String description = "MCP binding '" + safeBindingName + "' moved to " + statusLabel + "."
                + (details == null || details.isBlank() ? "" : " " + details.trim());

        attentionAlertService.resolveBySource(AttentionSourceType.MCP_STATUS_CHANGE, sourceId);
        attentionAlertService.create(new AttentionAlertService.CreateAttentionAlertCommand(
                channels,
                "MCP Binding Requires Review",
                description,
                AttentionResolutionPolicy.ACTION_REQUIRED,
                "/settings/mcp-bindings",
                System.currentTimeMillis(),
                AttentionSourceType.MCP_STATUS_CHANGE,
                sourceId));

        log.info("MCP status alert created [binding={}, status={}, channels={}]",
                bindingUuid, newStatus, channels.size());
    }

    private List<String> resolveAdminChannels() {
        List<String> channels = new ArrayList<>();
        try (var users = userRepository.list(0, Integer.MAX_VALUE)) {
            users.forEach(user -> {
                if (user == null || user.uuid() == null || user.uuid().isBlank()) {
                    return;
                }
                if (!user.isEnabled()) {
                    return;
                }
                if (!"ADMIN".equalsIgnoreCase(user.role())) {
                    return;
                }
                channels.add(user.uuid());
            });
        }
        return channels;
    }

    private static boolean isProblemStatus(McpBindingStatus status) {
        return status == McpBindingStatus.ERROR || status == McpBindingStatus.DRIFTED;
    }
}
