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
     * @param voiceFileUrl  {@code url_private} of an attached audio file, or {@code null}
     * @param voiceMimeType MIME type of the audio file (e.g. {@code "audio/ogg"}); non-null when voiceFileUrl is non-null
         * @param fileUrl       {@code url_private} of a non-audio file attachment, or {@code null}
         * @param fileMimeType  MIME type of {@code fileUrl}, or {@code null}
         * @param fileName      original file name when available, or {@code null}
     */
    record IncomingSlackMessage(
            String configId,
            String botToken,
            String channelId,
            String channelType,
            String userId,
            String text,
            String eventTs,
            String voiceFileUrl,
            String voiceMimeType,
            String fileUrl,
            String fileMimeType,
            String fileName
    ) {
        /**
         * Returns {@code true} when this message arrived in a direct-message conversation.
         */
        public boolean isDirectMessage() {
            return "im".equalsIgnoreCase(channelType);
        }

        /** Returns {@code true} when this message contains an audio file to be transcribed. */
        public boolean isVoice() {
            return voiceFileUrl != null;
        }

        /** Returns {@code true} when this message contains a non-audio file attachment. */
        public boolean isFile() {
            return fileUrl != null;
        }
    }
}
