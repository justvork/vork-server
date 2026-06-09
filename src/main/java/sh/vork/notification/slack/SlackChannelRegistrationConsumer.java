package sh.vork.notification.slack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link SlackMessageConsumer} that handles admin-initiated channel registration.
 *
 * <p>An admin posts {@code register CODE} in a Slack channel (not a DM) after
 * adding the Vork bot.  This consumer validates the code, saves a
 * {@link sh.vork.notification.GlobalAddress}, and replies with a confirmation.
 *
 * <p>Runs at {@link Order#value() order=2} — after the DM registration consumer
 * but before the main chat consumer.
 */
@Component
@Order(2)
public class SlackChannelRegistrationConsumer implements SlackMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(SlackChannelRegistrationConsumer.class);

    private final SlackChannelRegistrationService channelRegService;
    private final SlackApiClient                  slackApiClient;

    public SlackChannelRegistrationConsumer(SlackChannelRegistrationService channelRegService,
                                             SlackApiClient slackApiClient) {
        this.channelRegService = channelRegService;
        this.slackApiClient    = slackApiClient;
    }

    @Override
    public boolean accepts(IncomingSlackMessage message) {
        if (message.isDirectMessage()) return false;  // only channels
        String text = message.text();
        return text != null && text.trim().toLowerCase().startsWith("register ");
    }

    @Override
    public boolean process(IncomingSlackMessage message) {
        String text      = message.text().trim();
        String code      = text.substring("register ".length()).trim().toUpperCase();
        String channelId = message.channelId();
        String configId  = message.configId();
        String botToken  = message.botToken();

        log.debug("ENTER process: [code={}, channelId={}, configId={}]", code, channelId, configId);

        if (code.isBlank()) {
            slackApiClient.sendMessage(botToken, channelId,
                    "Please include the registration code. Example: register ABCDEF12345678AB");
            return true;
        }

        // Resolve channel name for the label
        String channelName = slackApiClient.getChannelName(botToken, channelId);

        boolean ok = channelRegService.complete(configId, code, channelId, channelName);
        if (ok) {
            slackApiClient.sendMessage(botToken, channelId,
                    "✅ Channel registered! This channel will now receive Vork notifications.");
            log.info("Slack channel registration completed [channelId={}, configId={}]",
                    channelId, configId);
        } else {
            slackApiClient.sendMessage(botToken, channelId,
                    "❌ The registration code is invalid or has expired. "
                    + "Please start a new registration from the Vork admin settings.");
        }
        return true;
    }
}
