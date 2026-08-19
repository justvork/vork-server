package sh.vork.attention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import sh.vork.channel.ChannelProvider;
import sh.vork.channel.ChannelRef;
import sh.vork.channel.ChannelService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.mock.MapDatabaseRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttentionAlertServiceTest {

    private AttentionAlertService service;

    @BeforeEach
    void setUp() {
        DatabaseRepository<AttentionAlert> repository = new MapDatabaseRepository<>(AttentionAlert.class);
        ChannelProvider provider = new ChannelProvider() {
            @Override
            public String providerKey() {
                return "user";
            }

            @Override
            public Optional<ChannelRef> resolveByChannelName(String channelName) {
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

        service = new AttentionAlertService(repository, new ChannelService(List.of(provider)));
    }

    @Test
    void createAndDismissDismissableAlert() {
        AttentionAlert created = service.create(new AttentionAlertService.CreateAttentionAlertCommand(
                List.of("alice"),
                "MCP Drift",
                "Binding drifted",
                AttentionResolutionPolicy.DISMISSABLE,
                "",
                System.currentTimeMillis(),
                AttentionSourceType.MCP_STATUS_CHANGE,
                "binding-1"));

        assertEquals(1, service.listDueAlertsForChannel("alice").size());
        service.dismiss("alice", created.uuid());
        assertTrue(service.listDueAlertsForChannel("alice").isEmpty());
    }

    @Test
    void actionRequiredCannotBeDismissed() {
        AttentionAlert created = service.create(new AttentionAlertService.CreateAttentionAlertCommand(
                List.of("alice"),
                "Session waiting",
                "Needs input",
                AttentionResolutionPolicy.ACTION_REQUIRED,
                "/job-monitor.html?session=x",
                System.currentTimeMillis(),
                AttentionSourceType.SESSION_SUSPENSION,
                "session-x"));

        assertThrows(IllegalStateException.class, () -> service.dismiss("alice", created.uuid()));
    }

    @Test
    void remindMovesAlertToFuture() {
        AttentionAlert created = service.create(new AttentionAlertService.CreateAttentionAlertCommand(
                List.of("alice"),
                "Later",
                "Do this later",
                AttentionResolutionPolicy.DISMISSABLE,
                "",
                System.currentTimeMillis(),
                AttentionSourceType.CUSTOM,
                "x"));

        long future = System.currentTimeMillis() + 86_400_000L;
        service.remind("alice", created.uuid(), future);

        List<AttentionAlert> due = service.listDueAlertsForChannel("alice");
        assertFalse(due.stream().anyMatch(a -> a.uuid().equals(created.uuid())));
    }
}
