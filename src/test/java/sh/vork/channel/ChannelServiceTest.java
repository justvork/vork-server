package sh.vork.channel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChannelServiceTest {

    @Test
    void resolveByChannelName_isCaseInsensitive() {
        ChannelProvider provider = new ChannelProvider() {
            @Override
            public String providerKey() {
                return "test";
            }

            @Override
            public Optional<ChannelRef> resolveByChannelName(String channelName) {
                if ("alice".equalsIgnoreCase(channelName)) {
                    return Optional.of(new ChannelRef("alice", "Alice", "test"));
                }
                return Optional.empty();
            }

            @Override
            public List<ChannelRef> search(String query, int limit) {
                return List.of();
            }
        };

        ChannelService service = new ChannelService(List.of(provider));
        assertEquals("alice", service.resolveByChannelName("ALICE").orElseThrow().channelName());
    }

    @Test
    void assertChannelNameAvailable_rejectsCaseInsensitiveConflict() {
        ChannelProvider provider = new ChannelProvider() {
            @Override
            public String providerKey() {
                return "test";
            }

            @Override
            public Optional<ChannelRef> resolveByChannelName(String channelName) {
                if ("alice".equalsIgnoreCase(channelName)) {
                    return Optional.of(new ChannelRef("Alice", "Alice", "test"));
                }
                return Optional.empty();
            }

            @Override
            public List<ChannelRef> search(String query, int limit) {
                return List.of();
            }
        };

        ChannelService service = new ChannelService(List.of(provider));
        assertThrows(IllegalArgumentException.class, () -> service.assertChannelNameAvailable("aLiCe"));
    }

    @Test
    void notifyChannelsWithUrls_routesViaResolvedProviderOnly() {
        AtomicReference<Map<ChannelRef, String>> delivered = new AtomicReference<>(Map.of());
        AtomicInteger otherProviderCalls = new AtomicInteger(0);

        ChannelProvider campaignProvider = new ChannelProvider() {
            @Override
            public String providerKey() {
                return "campaign";
            }

            @Override
            public Optional<ChannelRef> resolveByChannelName(String channelName) {
                if ("alice".equalsIgnoreCase(channelName)) {
                    return Optional.of(new ChannelRef("alice", "Alice", "campaign"));
                }
                return Optional.empty();
            }

            @Override
            public List<ChannelRef> search(String query, int limit) {
                return List.of();
            }

            @Override
            public void notifyChannelsWithUrls(Map<ChannelRef, String> channelRefsToUrl,
                                               String subject,
                                               String message) {
                delivered.set(channelRefsToUrl);
            }
        };

        ChannelProvider otherProvider = new ChannelProvider() {
            @Override
            public String providerKey() {
                return "other";
            }

            @Override
            public Optional<ChannelRef> resolveByChannelName(String channelName) {
                return Optional.empty();
            }

            @Override
            public List<ChannelRef> search(String query, int limit) {
                return List.of();
            }

            @Override
            public void notifyChannelsWithUrls(Map<ChannelRef, String> channelRefsToUrl,
                                               String subject,
                                               String message) {
                otherProviderCalls.incrementAndGet();
            }
        };

        ChannelService service = new ChannelService(List.of(campaignProvider, otherProvider));
        service.notifyChannelsWithUrls(Map.of(
                "ALICE", "https://example.test/chat?session=1",
                "missing", "https://example.test/chat?session=2"),
                "Request Information",
                "Please respond");

        assertEquals(1, delivered.get().size());
        assertEquals(0, otherProviderCalls.get());
        ChannelRef ref = delivered.get().keySet().iterator().next();
        assertEquals("alice", ref.channelName());
    }
}
