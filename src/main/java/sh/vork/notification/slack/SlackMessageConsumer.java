package sh.vork.notification.slack;

/**
 * Represents a single inbound Slack event delivered via Socket Mode.
 *
 * <p>Consumers implement this interface to receive Slack messages.  The
 * {@link SlackSocketModeService} calls {@link #accepts} first and only invokes
 * {@link #process} when {@code true} is returned.  Consumers are ordered via
 * Spring's {@link org.springframework.core.annotation.Order} annotation —
 * lower values run first.
 */
public interface SlackMessageConsumer {

    /**
     * Returns {@code true} if this consumer wants to handle the given message.
     */
    boolean accepts(IncomingSlackMessage message);

    /**
     * Processes the message.  Only called when {@link #accepts} returns {@code true}.
     *
     * @return {@code true} if processing is considered complete and no further
     *         consumers should be invoked; {@code false} to allow fall-through
     */
    boolean process(IncomingSlackMessage message);

    // ── Value type ────────────────────────────────────────────────────────────

    /**
     * Immutable snapshot of a Slack Socket Mode {@code events_api / message} event.
     *
     * @param configId      the {@link sh.vork.notification.NotificationProviderConfig} UUID
     *                      that owns the bot receiving this event
     * @param botToken      {@code xoxb-…} token used to reply
     * @param channelId     Slack conversation ID (e.g. {@code D01ABCDE} for DMs,
     *                      {@code C01ABCDE} for channels)
     * @param channelType   {@code "im"} for DMs, {@code "channel"} for public channels,
     *                      {@code "group"} for private channels
     * @param userId        Slack member ID of the sending user (e.g. {@code U01ABCDE})
     * @param text          the plain-text body of the message
     * @param eventTs       Slack event timestamp (e.g. {@code "1609459200.000001"})
     */
    record IncomingSlackMessage(
            String configId,
            String botToken,
            String channelId,
            String channelType,
            String userId,
            String text,
            String eventTs
    ) {
        /**
         * Returns {@code true} when this message arrived in a direct-message conversation.
         */
        public boolean isDirectMessage() {
            return "im".equalsIgnoreCase(channelType);
        }
    }
}
