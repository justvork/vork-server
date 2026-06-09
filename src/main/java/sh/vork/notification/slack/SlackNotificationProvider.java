package sh.vork.notification.slack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import sh.vork.notification.Notification;
import sh.vork.notification.NotificationException;
import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.NotificationProvider;
import sh.vork.notification.SettingDefinition;

/**
 * {@link NotificationProvider} that delivers notifications via the Slack Web API
 * and maintains real-time event delivery via Slack Socket Mode.
 *
 * <h3>Required settings</h3>
 * <ul>
 *   <li>{@code botToken} — {@code xoxb-…} bot OAuth token (chat:write scope)</li>
 *   <li>{@code appToken} — {@code xapp-…} app-level token (connections:write scope) for Socket Mode</li>
 * </ul>
 */
@Component
public class SlackNotificationProvider implements NotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(SlackNotificationProvider.class);

    /** Accepts Slack IDs: U (users), C (public channels), W (workspace), D (DMs), G (private groups). */
    private static final Pattern SLACK_ID_RE =
            Pattern.compile("^[UCWDG][A-Z0-9]{6,}$");

    private static final List<SettingDefinition> DEFINITIONS = List.of(
            SettingDefinition.required("botToken",  "Bot Token (xoxb-…)",  "password",
                    "xoxb-your-bot-token"),
            SettingDefinition.required("appToken",  "App-Level Token (xapp-…)", "password",
                    "xapp-your-app-token")
    );

    private final SlackApiClient slackApiClient;

    public SlackNotificationProvider(SlackApiClient slackApiClient) {
        this.slackApiClient = slackApiClient;
    }

    @Override
    public String getProviderKey() {
        return "slack";
    }

    @Override
    public String getDisplayName() {
        return "Slack";
    }

    @Override
    public Set<NotificationMediaType> getSupportedMediaTypes() {
        return Set.of(NotificationMediaType.SLACK);
    }

    /**
     * Slack DMs require prior opt-in — the user must message the bot first.
     */
    @Override
    public boolean supportsDirectAddress() {
        return false;
    }

    @Override
    public List<SettingDefinition> getSettingDefinitions() {
        return DEFINITIONS;
    }

    @Override
    public Map<String, String> validate(Map<String, String> settings) {
        Map<String, String> errors = new LinkedHashMap<>();
        String botToken = settings.getOrDefault("botToken", "").trim();
        String appToken = settings.getOrDefault("appToken", "").trim();
        if (botToken.isBlank()) errors.put("botToken", "Bot Token (xoxb-…) is required.");
        else if (!botToken.startsWith("xoxb-")) errors.put("botToken", "Bot Token must start with xoxb-.");
        if (appToken.isBlank()) errors.put("appToken", "App-Level Token (xapp-…) is required.");
        else if (!appToken.startsWith("xapp-")) errors.put("appToken", "App-Level Token must start with xapp-.");
        return errors;
    }

    @Override
    public boolean supportsGlobalAddresses() {
        return true;
    }

    @Override
    public String getGlobalAddressSetupInstructions() {
        return "To register a Slack channel as a shared notification target:\n"
                + "1. Add the Vork bot to the target Slack channel.\n"
                + "2. Click \"Register Slack Channel\" below — you will receive a one-time code.\n"
                + "3. In the Slack channel, type: register <code>\n"
                + "The channel will be registered automatically once the bot receives the message.";
    }

    @Override
    public String validateAddress(NotificationMediaType mediaType, String address) {
        if (address == null || address.isBlank()) {
            return "Slack channel or member ID is required.";
        }
        if (!SLACK_ID_RE.matcher(address.trim()).matches()) {
            return "Enter a valid Slack ID (e.g. C01ABCDE for a channel, U01ABCDE for a user).";
        }
        return null;
    }

    @Override
    public void send(Notification notification, Map<String, String> settings)
            throws NotificationException {
        String botToken = settings.getOrDefault("botToken", "").trim();
        if (botToken.isBlank()) {
            throw new NotificationException("Slack botToken is not configured.");
        }

        String text = notification.title()
                + (notification.body() != null && !notification.body().isBlank()
                        ? "\n" + notification.body() : "");

        log.debug("ENTER send: [recipients={}, title={}]",
                notification.recipients().size(), notification.title());

        for (String channelId : notification.recipients()) {
            try {
                slackApiClient.sendMessage(botToken, channelId, text);
                log.debug("Step: message sent [channel={}]", channelId);
            } catch (SlackApiException e) {
                log.warn("Slack send failed [channel={}]: {}", channelId, e.getMessage());
                throw new NotificationException(
                        "Slack delivery failed to " + channelId + ": " + e.getMessage(), e);
            }
        }
        log.debug("EXIT send: [recipients={}]", notification.recipients().size());
    }
}
