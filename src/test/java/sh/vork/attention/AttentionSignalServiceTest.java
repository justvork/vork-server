package sh.vork.attention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.vork.channel.ChannelProvider;
import sh.vork.channel.ChannelRef;
import sh.vork.channel.ChannelService;
import sh.vork.mcp.model.McpBindingStatus;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.mock.MapDatabaseRepository;
import sh.vork.security.VorkUser;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttentionSignalServiceTest {

    private AttentionAlertService alertService;
    private AttentionSignalService signalService;

    @BeforeEach
    void setUp() {
        DatabaseRepository<AttentionAlert> attentionRepo = new MapDatabaseRepository<>(AttentionAlert.class);
        DatabaseRepository<VorkUser> userRepo = new MapDatabaseRepository<>(VorkUser.class);

        userRepo.save(new VorkUser("admin", "Admin", "x", "ADMIN", true, 1L, 1L));
        userRepo.save(new VorkUser("root", "Root", "x", "ADMIN", true, 1L, 1L));
        userRepo.save(new VorkUser("alice", "Alice", "x", "USER", true, 1L, 1L));

        ChannelProvider provider = new ChannelProvider() {
            @Override
            public String providerKey() {
                return "user";
            }

            @Override
            public Optional<ChannelRef> resolveByChannelName(String channelName) {
                if ("admin".equalsIgnoreCase(channelName)) {
                    return Optional.of(new ChannelRef("admin", "Admin", "user"));
                }
                if ("root".equalsIgnoreCase(channelName)) {
                    return Optional.of(new ChannelRef("root", "Root", "user"));
                }
                if ("alice".equalsIgnoreCase(channelName)) {
                    return Optional.of(new ChannelRef("alice", "Alice", "user"));
                }
                return Optional.empty();
            }

            @Override
            public List<ChannelRef> search(String query, int limit) {
                return List.of();
            }
        };

        alertService = new AttentionAlertService(attentionRepo, new ChannelService(List.of(provider)));
        signalService = new AttentionSignalService(alertService, userRepo);
    }

    @Test
    void sessionSuspensionDoesNotCreateRequesterAlert() {
        signalService.onSessionSuspended("sess-1", "alice", "sendNotification", "Approval required");

        List<AttentionAlert> due = alertService.listDueAlertsForChannel("alice");
        assertTrue(due.isEmpty());

        signalService.onSessionResumed("sess-1");
        assertTrue(alertService.listDueAlertsForChannel("alice").isEmpty());
    }

    @Test
    void mcpErrorCreatesAdminAlertAndRecoveryResolvesIt() {
        signalService.onMcpStatusChanged("binding-1", "Main MCP", McpBindingStatus.ACTIVE,
                McpBindingStatus.ERROR, "Network unreachable");

        List<AttentionAlert> adminAlerts = alertService.listDueAlertsForChannel("admin");
        assertEquals(1, adminAlerts.size());
        List<AttentionAlert> rootAlerts = alertService.listDueAlertsForChannel("root");
        assertEquals(1, rootAlerts.size());
        assertEquals(adminAlerts.getFirst().uuid(), rootAlerts.getFirst().uuid());
        assertEquals(AttentionSourceType.MCP_STATUS_CHANGE, adminAlerts.getFirst().sourceType());
        assertTrue(alertService.listDueAlertsForChannel("alice").isEmpty());

        signalService.onMcpStatusChanged("binding-1", "Main MCP", McpBindingStatus.ERROR,
                McpBindingStatus.INACTIVE, "Recovered");
        assertFalse(alertService.listDueAlertsForChannel("admin").stream()
                .anyMatch(a -> a.sourceType() == AttentionSourceType.MCP_STATUS_CHANGE));
        assertFalse(alertService.listDueAlertsForChannel("root").stream()
            .anyMatch(a -> a.sourceType() == AttentionSourceType.MCP_STATUS_CHANGE));
    }
}
