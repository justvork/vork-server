package sh.vork.channel;

/**
 * Addressable channel reference returned by channel providers.
 */
public record ChannelRef(
        String channelName,
        String displayName,
        String providerKey
) {
}
