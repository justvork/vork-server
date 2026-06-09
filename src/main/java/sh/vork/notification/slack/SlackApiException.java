package sh.vork.notification.slack;

/**
 * Thrown when a Slack Web API or Socket Mode call fails.
 */
public class SlackApiException extends RuntimeException {

    public SlackApiException(String message) {
        super(message);
    }

    public SlackApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
