package sh.vork.notification.slack;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import sh.vork.notification.NotificationProviderConfig;
import com.jadaptive.orm.DatabaseRepository;
import com.jadaptive.orm.SearchQuery;
import com.jadaptive.orm.SortOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages long-lived Slack Socket Mode WebSocket connections — one per configured
 * Slack provider instance.
 *
 * <p>On application ready it scans all persisted {@link NotificationProviderConfig}s
 * with {@code providerKey=slack} and opens a WebSocket connection for each one.
 * Each connection runs on its own virtual thread in a reconnect loop: when the
 * socket closes (30-minute WSS URL expiry or network drop) a new WSS URL is
 * requested immediately and the connection is re-established.
 *
 * <p>Incoming events are dispatched to all {@link SlackMessageConsumer} beans in
 * order.  The first consumer that returns {@code true} from
 * {@link SlackMessageConsumer#process} stops further dispatch for that event.
 */
@Service
public class SlackSocketModeService {

    private static final Logger log = LoggerFactory.getLogger(SlackSocketModeService.class);

    private final DatabaseRepository<NotificationProviderConfig> configRepo;
    private final SlackApiClient                                 slackApiClient;
    private final List<SlackMessageConsumer>                     consumers;
    private final ObjectMapper                                   objectMapper;
    private final HttpClient                                     httpClient;

    /** configId → thread stop flag ({@code [0] == true} to stop). */
    private final ConcurrentHashMap<String, boolean[]> stopFlags = new ConcurrentHashMap<>();

    public SlackSocketModeService(
            DatabaseRepository<NotificationProviderConfig> configRepo,
            SlackApiClient slackApiClient,
            List<SlackMessageConsumer> consumers,
            ObjectMapper objectMapper) {
        this.configRepo    = configRepo;
        this.slackApiClient = slackApiClient;
        this.consumers     = consumers;
        this.objectMapper  = objectMapper;
        this.httpClient    = HttpClient.newHttpClient();
    }

    // ── Startup ───────────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.debug("ENTER onApplicationReady: scanning Slack provider configs");
        try (var stream = configRepo.search(
                0, Integer.MAX_VALUE, "displayName", SortOrder.ASC,
                SearchQuery.eq("providerKey", "slack"))) {
            stream.forEach(this::startConnection);
        }
        log.debug("EXIT onApplicationReady");
    }

    /**
     * Starts (or restarts) the Socket Mode connection for the given config.
     * Safe to call more than once — stops any existing connection first.
     */
    public void startConnection(NotificationProviderConfig config) {
        String configId = config.uuid();
        log.info("Starting Slack Socket Mode connection [configId={}]", configId);

        // Signal any existing loop to stop
        boolean[] existing = stopFlags.get(configId);
        if (existing != null) existing[0] = true;

        boolean[] stopFlag = { false };
        stopFlags.put(configId, stopFlag);

        Thread.ofVirtual()
              .name("slack-socket-" + configId)
              .start(() -> connectionLoop(config, stopFlag));
    }

    /**
     * Signals the Socket Mode connection for the given config to stop and
     * removes its registration.  Safe to call with an unknown configId.
     */
    public void stopConnection(String configId) {
        boolean[] flag = stopFlags.remove(configId);
        if (flag != null) {
            flag[0] = true;
            log.info("Stopped Slack Socket Mode connection [configId={}]", configId);
        }
    }

    // ── Connection loop ───────────────────────────────────────────────────────

    private void connectionLoop(NotificationProviderConfig config, boolean[] stopFlag) {
        String configId = config.uuid();
        String appToken = config.settings().getOrDefault("appToken", "").trim();
        String botToken = config.settings().getOrDefault("botToken", "").trim();

        if (appToken.isBlank() || botToken.isBlank()) {
            log.warn("Slack config missing appToken or botToken — Socket Mode not started [configId={}]", configId);
            return;
        }

        int attempt = 0;
        while (!stopFlag[0] && !Thread.currentThread().isInterrupted()) {
            attempt++;
            log.debug("Socket Mode connection attempt {} [configId={}]", attempt, configId);

            try {
                // Obtain a fresh WSS URL (expires ~30 min)
                String wssUrl = slackApiClient.openSocketModeConnection(appToken);

                CompletableFuture<Void> closedFuture = new CompletableFuture<>();

                WebSocket ws = httpClient.newWebSocketBuilder()
                        .buildAsync(URI.create(wssUrl), new SlackWebSocketListener(
                                configId, botToken, closedFuture))
                        .join();

                log.info("Slack Socket Mode connected [configId={}, attempt={}]", configId, attempt);

                // Block this virtual thread until the socket closes
                closedFuture.join();
                ws.abort();
                log.info("Slack Socket Mode connection closed [configId={}] — will reconnect", configId);

            } catch (Exception e) {
                if (stopFlag[0]) break;
                log.warn("Slack Socket Mode error [configId={}, attempt={}]: {}",
                        configId, attempt, e.getMessage());
            }

            if (stopFlag[0]) break;

            // Brief back-off before reconnecting
            try { Thread.sleep(2_000); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
        }
        log.debug("Slack Socket Mode connection loop ended [configId={}]", configId);
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private class SlackWebSocketListener implements WebSocket.Listener {

        private final String                  configId;
        private final String                  botToken;
        private final CompletableFuture<Void> closedFuture;
        private final StringBuilder           accumulator = new StringBuilder();

        SlackWebSocketListener(String configId, String botToken,
                                CompletableFuture<Void> closedFuture) {
            this.configId     = configId;
            this.botToken     = botToken;
            this.closedFuture = closedFuture;
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            accumulator.append(data);
            ws.request(1);
            if (!last) return null;

            String raw = accumulator.toString();
            accumulator.setLength(0);

            Thread.ofVirtual().name("slack-event-" + configId).start(() -> handleRawEvent(raw, ws));
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            log.debug("Slack WebSocket closed [configId={}, status={}, reason={}]",
                    configId, statusCode, reason);
            closedFuture.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.warn("Slack WebSocket error [configId={}]: {}", configId, error.getMessage());
            closedFuture.completeExceptionally(error);
        }

        private void handleRawEvent(String raw, WebSocket ws) {
            log.debug("Slack raw event [configId={}]: {}", configId,
                    raw.length() > 200 ? raw.substring(0, 200) + "…" : raw);
            try {
                Map<String, Object> envelope = objectMapper.readValue(
                        raw, new TypeReference<>() {});

                String envelopeId = (String) envelope.get("envelope_id");
                String type       = (String) envelope.get("type");

                // ACK every envelope within Slack's 3-second window
                if (envelopeId != null) {
                    ws.sendText("{\"envelope_id\":\"" + envelopeId + "\"}", true);
                }

                if ("hello".equals(type)) {
                    log.debug("Slack hello received [configId={}]", configId);
                    return;
                }

                if ("events_api".equals(type)) {
                    dispatchEventsApi(envelope);
                }

            } catch (Exception e) {
                log.warn("Failed to parse Slack event [configId={}]: {}", configId, e.getMessage());
            }
        }

        @SuppressWarnings("unchecked")
        private void dispatchEventsApi(Map<String, Object> envelope) {
            Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
            if (payload == null) return;

            Map<String, Object> event = (Map<String, Object>) payload.get("event");
            if (event == null) return;

            String eventType = (String) event.get("type");
            if (!"message".equals(eventType)) return;

            // Ignore bot's own messages (subtype = bot_message, or bot_id present)
            if (event.get("bot_id") != null) return;
            String subtype = (String) event.get("subtype");
            if (subtype != null && !subtype.isBlank()) return;

            String channelId   = (String) event.get("channel");
            String channelType = (String) event.getOrDefault("channel_type", "");
            String userId      = (String) event.get("user");
            String text        = (String) event.getOrDefault("text", "");
            String eventTs     = (String) event.getOrDefault("ts", "");

            if (channelId == null || userId == null) return;

            SlackMessageConsumer.IncomingSlackMessage msg =
                    new SlackMessageConsumer.IncomingSlackMessage(
                            configId, botToken, channelId, channelType,
                            userId, text, eventTs);

            log.debug("Dispatching Slack message [configId={}, channel={}, type={}, from={}]",
                    configId, channelId, channelType, userId);

            for (SlackMessageConsumer consumer : consumers) {
                try {
                    if (consumer.accepts(msg) && consumer.process(msg)) {
                        break; // handled — stop chain
                    }
                } catch (Exception e) {
                    log.warn("SlackMessageConsumer {} threw [configId={}]: {}",
                            consumer.getClass().getSimpleName(), configId, e.getMessage(), e);
                }
            }
        }
    }
}
