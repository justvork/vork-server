package sh.vork.notification.slack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link SlackMessageConsumer} that handles DM-based user self-registration.
 *
 * <p>A user sends {@code register CODE} in a Slack direct message with the bot.
 * This consumer extracts the code, delegates to {@link SlackRegistrationService}
 * to complete the registration, and replies with a confirmation.
 *
 * <p>Only processes direct-message events ({@link SlackMessageConsumer.IncomingSlackMessage#isDirectMessage()}).
 * Runs at {@link Order#value() order=1} so it fires before the chat consumer.
 */
@Component
@Order(1)
public class SlackDmRegistrationConsumer implements SlackMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(SlackDmRegistrationConsumer.class);

    private final SlackRegistrationService registrationService;
    private final SlackApiClient           slackApiClient;

    public SlackDmRegistrationConsumer(SlackRegistrationService registrationService,
                                        SlackApiClient slackApiClient) {
        this.registrationService = registrationService;
        this.slackApiClient      = slackApiClient;
    }

    @Override
    public boolean accepts(IncomingSlackMessage message) {
        if (!message.isDirectMessage()) return false;
        String text = message.text();
        return text != null && text.trim().toLowerCase().startsWith("register ");
    }

    @Override
    public boolean process(IncomingSlackMessage message) {
        String text      = message.text().trim();
        String code      = text.substring("register ".length()).trim().toUpperCase();
        String channelId = message.channelId();   // DM channel — used for replies
        String userId    = message.userId();
        String configId  = message.configId();
        String botToken  = message.botToken();

        log.debug("ENTER process: [code={}, userId={}, configId={}]", code, userId, configId);

        if (code.isBlank()) {
            slackApiClient.sendMessage(botToken, channelId,
                    "Please include the registration code. Example: register ABCDEF12345678AB");
            return true;
        }

        boolean ok = registrationService.complete(configId, code, userId, botToken);
        if (ok) {
            slackApiClient.sendMessage(botToken, channelId,
                    "✅ Registration successful! Your Slack account is now linked to Vork.");
            log.info("Slack DM registration completed via consumer [userId={}, configId={}]",
                    userId, configId);
        } else {
            slackApiClient.sendMessage(botToken, channelId,
                    "❌ The registration code is invalid or has expired. "
                    + "Please start a new registration from your Vork profile.");
            log.warn("Slack DM registration failed [code={}, userId={}, configId={}]",
                    code, userId, configId);
        }
        return true;
    }
}
