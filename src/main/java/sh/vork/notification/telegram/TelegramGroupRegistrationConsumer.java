package sh.vork.notification.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link TelegramMessageConsumer} that handles the Telegram group registration flow.
 *
 * <p>When an admin types {@code /register CODE} inside a Telegram group that the
 * bot has been added to, this consumer:
 * <ol>
 *   <li>Extracts the one-time code from the message text.</li>
 *   <li>Calls {@link TelegramGroupRegistrationService#complete} to validate the
 *       code and persist the {@link sh.vork.notification.GlobalAddress}.</li>
 *   <li>Sends a confirmation (or error) message back to the group.</li>
 * </ol>
 *
 * <p>Runs with {@link Order}(2) — after other high-priority consumers but before
 * the catch-all {@link TelegramChatConsumer} (Order 10).
 */
@Component
@Order(2)
public class TelegramGroupRegistrationConsumer implements TelegramMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelegramGroupRegistrationConsumer.class);

    private final TelegramGroupRegistrationService groupRegistrationService;
    private final TelegramApiClient                telegramApiClient;

    public TelegramGroupRegistrationConsumer(
            TelegramGroupRegistrationService groupRegistrationService,
            TelegramApiClient telegramApiClient) {
        this.groupRegistrationService = groupRegistrationService;
        this.telegramApiClient        = telegramApiClient;
    }

    @Override
    public boolean accepts(IncomingMessage message) {
        if (message.isCallback()) return false;
        if (!message.isGroupChat()) return false;
        String text = message.text();
        return text != null && text.trim().startsWith("/register");
    }

    @Override
    public void process(IncomingMessage message) {
        log.debug("ENTER process: [configId={}, chatId={}, chatTitle={}]",
                message.configId(), message.chatId(), message.chatTitle());

        String text = message.text().trim();
        // Extract code: "/register CODE" — take everything after "/register "
        String code = text.length() > 9 ? text.substring(9).trim() : "";

        if (code.isBlank()) {
            telegramApiClient.sendText(message.botToken(), message.chatId(),
                    "⚠️ Please include the registration code. Example: /register ABCDEF123456");
            return;
        }

        boolean completed = groupRegistrationService.complete(
                message.configId(), code, message.chatId(), message.chatTitle());

        if (completed) {
            String groupName = message.chatTitle() != null && !message.chatTitle().isBlank()
                    ? message.chatTitle() : "this group";
            telegramApiClient.sendText(message.botToken(), message.chatId(),
                    "✅ \"" + groupName + "\" has been registered as a Vork notification target.");
            log.info("Group registration completed via /register [configId={}, chatId={}, chatTitle={}]",
                    message.configId(), message.chatId(), message.chatTitle());
        } else {
            telegramApiClient.sendText(message.botToken(), message.chatId(),
                    "❌ Registration code not found or expired. "
                    + "Please request a new code from the Vork admin panel.");
            log.warn("Group registration failed [configId={}, chatId={}, code={}]",
                    message.configId(), message.chatId(), code);
        }
    }
}
