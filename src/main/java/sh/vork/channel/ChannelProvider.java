package sh.vork.channel;

import java.util.List;
import java.util.Optional;

/**
 * Provider-specific source of globally addressable channels.
 */
public interface ChannelProvider {

    String providerKey();

    Optional<ChannelRef> resolveByChannelName(String channelName);

    List<ChannelRef> search(String query, int limit);
}
