package sh.vork.channel;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Provider-specific source of globally addressable channels.
 */
public interface ChannelProvider {

    String providerKey();

    Optional<ChannelRef> resolveByChannelName(String channelName);

    List<ChannelRef> search(String query, int limit);

    default void notifyChannelsWithUrls(Map<ChannelRef, String> channelRefsToUrl,
                                        String subject,
                                        String message) {
        // Default no-op. Providers that can deliver campaign notifications should override.
    }
}
