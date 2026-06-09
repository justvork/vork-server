package sh.vork.ai.slack;

import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import sh.vork.ai.AiProvider;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.service.ChatService;
import sh.vork.setup.SystemSettings;
import sh.vork.setup.SystemSettingsService;

/**
 * In-memory registry that maps a Slack DM {@code channelId} to the active AI session UUID.
 *
 * <p>Sessions are created on first contact and discarded when the user sends {@code /new}.
 * The registry is in-memory only; a server restart begins a new session automatically.
 */
@Service
public class SlackSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SlackSessionRegistry.class);

    /** DM channelId -> sessionUuid */
    private final ConcurrentHashMap<String, String> activeSessions = new ConcurrentHashMap<>();

    private final ChatService           chatService;
    private final SystemSettingsService systemSettingsService;

    public SlackSessionRegistry(ChatService chatService,
                                 SystemSettingsService systemSettingsService) {
        this.chatService           = chatService;
        this.systemSettingsService = systemSettingsService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the session UUID for the given DM channel, creating a new session if none exists.
     *
     * @param username  authenticated Vork username
     * @param configId  NotificationProviderConfig UUID (which bot)
     * @param channelId Slack DM channel ID (starts with {@code D})
     * @param botToken  bot token (stored in session env for replies)
     * @return session UUID (never null)
     */
    public String getOrCreate(String username, String configId, String channelId, String botToken) {
        return activeSessions.computeIfAbsent(channelId,
                id -> createSession(username, configId, channelId, botToken));
    }

    /**
     * Returns the active session UUID for the given DM channel, or {@code null} if none is tracked.
     */
    public String get(String channelId) {
        return activeSessions.get(channelId);
    }

    /**
     * Removes the active session mapping for the given channel.
     * The next message from this channel will create a fresh session.
     */
    public void reset(String channelId) {
        String removed = activeSessions.remove(channelId);
        log.info("Slack session reset [channelId={}, removedSession={}]", channelId, removed);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String createSession(String username, String configId,
                                  String channelId, String botToken) {
        String providerName = resolveDefaultProviderName();
        AiSession session = chatService.createSlackSession(
                username, configId, channelId, botToken, providerName);
        log.info("Slack session created [channelId={}, sessionUuid={}, user={}, provider={}]",
                channelId, session.uuid(), username, providerName);
        return session.uuid();
    }

    private String resolveDefaultProviderName() {
        SystemSettings gs = systemSettingsService.getGlobal();
        if (gs != null && gs.defaultProvider() != null && !gs.defaultProvider().isBlank()) {
            return gs.defaultProvider();
        }
        return AiProvider.GEMINI.name();
    }
}
