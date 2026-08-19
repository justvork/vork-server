package sh.vork.channel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

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
}
