package sh.vork.notification.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import sh.vork.orm.DatabaseRepository;
import sh.vork.orm.SearchQuery;
import sh.vork.orm.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import sh.vork.ai.entity.AiChatMessage;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.protocol.UiEventFrame;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.filesystem.FileDescriptor;
import sh.vork.ai.service.ChatService;
import sh.vork.ai.slack.SlackSessionRegistry;
import sh.vork.ai.slack.SlackSuspensionRenderer;
import sh.vork.ai.telegram.TelegramChatResumptionService;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.notification.NotificationMediaType;
import sh.vork.notification.user.UserNotificationMedia;
import sh.vork.transcription.AudioTranscriptionService;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

/**
 * {@link SlackMessageConsumer} that handles AI chat sessions in Slack DMs.
 *
 * <p>Only DM messages are processed ({@link SlackMessageConsumer.IncomingSlackMessage#isDirectMessage()}).
 * Channel messages are silently ignored so notifications-only channels remain quiet.
 *
 * <p>Runs at {@link Order#value() order=10} — after all registration consumers.
 *
 * <h3>Suspension handling</h3>
 * <ul>
 *   <li><b>SIMPLE</b> — the renderer sends a numbered action list; the next digit reply
 *       is looked up in the {@code pendingActions} map and dispatched as the action name.</li>
 *   <li><b>SINGLE_TEXT</b> — the renderer sends a plain prompt; the next message is
 *       stored in {@code pendingCaptures} and forwarded as the field value.</li>
 *   <li><b>WEB_FORM</b> — the renderer sends a URL (self-hosted or relay); a relay
 *       poll thread handles the response asynchronously.</li>
 * </ul>
 */
@Component
@Order(10)
public class SlackChatConsumer implements SlackMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(SlackChatConsumer.class);

    private final ChatService                              chatService;
    private final DatabaseRepository<AiSession>           sessionRepo;
    private final DatabaseRepository<UserNotificationMedia> mediaRepo;
    private final SlackSessionRegistry                    sessionRegistry;
    private final TelegramChatResumptionService           resumptionService;
    private final SlackSuspensionRenderer                 suspensionRenderer;
    private final SlackApiClient                          slackApiClient;
    private final AudioTranscriptionService               audioTranscriptionService;
    private final SessionFileSystem                       sessionFileSystem;

    /** DM channelId → pending single-field capture */
    private final ConcurrentHashMap<String, PendingCapture>       pendingCaptures = new ConcurrentHashMap<>();
    /** DM channelId → pending action choice */
    private final ConcurrentHashMap<String, PendingActionChoice>  pendingActions  = new ConcurrentHashMap<>();

    public SlackChatConsumer(ChatService chatService,
                              DatabaseRepository<AiSession> sessionRepo,
                              DatabaseRepository<UserNotificationMedia> mediaRepo,
                              SlackSessionRegistry sessionRegistry,
                              TelegramChatResumptionService resumptionService,
                              SlackSuspensionRenderer suspensionRenderer,
                              SlackApiClient slackApiClient,
                              ObjectMapper objectMapper,
                              AudioTranscriptionService audioTranscriptionService,
                              SessionFileSystem sessionFileSystem) {
        this.chatService              = chatService;
        this.sessionRepo              = sessionRepo;
        this.mediaRepo                = mediaRepo;
        this.sessionRegistry          = sessionRegistry;
        this.resumptionService        = resumptionService;
        this.suspensionRenderer       = suspensionRenderer;
        this.slackApiClient           = slackApiClient;
        this.audioTranscriptionService = audioTranscriptionService;
        this.sessionFileSystem = sessionFileSystem;
    }

    // ── SlackMessageConsumer ──────────────────────────────────────────────────

    @Override
    public boolean accepts(IncomingSlackMessage message) {
        return message.isDirectMessage();
    }

    @Override
    public boolean process(IncomingSlackMessage message) {
        String channelId = message.channelId();
        String botToken  = message.botToken();
        String configId  = message.configId();
        String text      = message.text();
        List<String> attachmentRefs = new ArrayList<>();

        log.debug("ENTER SlackChatConsumer.process: [channel={}, userId={}]",
                channelId, message.userId());

        try {
            // Voice note: transcribe to text before routing
            if ((text == null || text.isBlank()) && message.isVoice()) {
                text = transcribeVoice(message);
            }

            if ((text == null || text.isBlank()) && !message.isFile()) {
                log.debug("Ignoring empty text from channel={}", channelId);
                return true;
            }

            // /new — reset session
            if ("/new".equalsIgnoreCase(text.trim())) {
                sessionRegistry.reset(channelId);
                pendingCaptures.remove(channelId);
                pendingActions.remove(channelId);
                slackApiClient.sendMessage(botToken, channelId,
                        "New session started. How can I help?");
                return true;
            }

            // Pending action choice (numeric reply for SIMPLE form)?
            PendingActionChoice actionChoice = pendingActions.get(channelId);
            if (actionChoice != null && text.trim().matches("\\d+")) {
                pendingActions.remove(channelId);
                handleActionChoice(actionChoice, text.trim(), channelId, botToken);
                return true;
            }

            // Pending single-field capture?
            PendingCapture capture = pendingCaptures.remove(channelId);
            if (capture != null) {
                handleFieldCapture(capture, text, channelId, botToken);
                return true;
            }

            // Normal message — look up user by Slack member ID
            String username = resolveUsername(message.userId(), channelId, botToken);
            if (username == null) return true; // not registered — message sent

            String sessionUuid = sessionRegistry.getOrCreate(username, configId, channelId, botToken);
            log.debug("Sending Slack message to session [sessionUuid={}, user={}]",
                    sessionUuid, username);

            if (message.isFile()) {
                String attachmentRef = ingestAttachment(message, botToken, channelId, sessionUuid);
                if (attachmentRef != null) {
                    attachmentRefs.add(attachmentRef);
                }
            }

            if ((text == null || text.isBlank()) && attachmentRefs.isEmpty()) {
                log.debug("Ignoring empty text/file after ingest failure from channel={}", channelId);
                return true;
            }

                String effectiveText = (text == null || text.isBlank())
                    ? "Please analyze the attached file."
                    : text;

            AiChatMessage response = chatService.sendMessageAsUser(
                    username, sessionUuid, effectiveText, attachmentRefs, null);

            if (response == null) {
                renderLatestSuspension(sessionUuid, channelId, botToken);
            } else {
                String reply = response.content();
                if (reply != null && !reply.isBlank()) {
                    slackApiClient.sendMessage(botToken, channelId, reply);
                }
                sendAiAttachments(response, sessionUuid, botToken, channelId);
            }
        } catch (Exception e) {
            log.warn("Error processing Slack message [channel={}]: {}", channelId, e.getMessage(), e);
            slackApiClient.sendMessage(botToken, channelId,
                    "Sorry, an error occurred. Please try again.");
        }
        return true;
    }

    private void sendAiAttachments(AiChatMessage response,
                                   String defaultSessionUuid,
                                   String botToken,
                                   String channelId) {
        if (response == null || response.attachments() == null || response.attachments().isEmpty()) {
            return;
        }
        for (AiChatMessage.AttachmentRef ref : response.attachments()) {
            try {
                OutboundAttachment attachment = resolveOutboundAttachment(ref, defaultSessionUuid);
                if (attachment == null || attachment.bytes() == null || attachment.bytes().length == 0) {
                    continue;
                }
                slackApiClient.sendFile(
                        botToken,
                        channelId,
                        attachment.name(),
                        attachment.mimeType(),
                        attachment.bytes(),
                        null);
                log.info("Sent AI attachment to Slack [channel={}, file={}, bytes={}]",
                        channelId, attachment.name(), attachment.bytes().length);
            } catch (Exception ex) {
                log.warn("Failed to send AI attachment to Slack [channel={}, ref={}]: {}",
                        channelId, ref == null ? null : ref.name(), ex.getMessage());
            }
        }
    }

    private OutboundAttachment resolveOutboundAttachment(AiChatMessage.AttachmentRef ref,
                                                         String defaultSessionUuid) {
        if (ref == null) {
            return null;
        }
        String directUrl = normalizeSessionDownloadUrl(ref.url());
        if (directUrl == null) {
            directUrl = normalizeSessionDownloadUrl(ref.uuid());
        }

        if (directUrl != null) {
            String path = queryParam(directUrl, "path");
            if (path == null || path.isBlank()) {
                return null;
            }
            String sessionUuid = queryParam(directUrl, "sessionUuid");
            if (sessionUuid == null || sessionUuid.isBlank()) {
                sessionUuid = defaultSessionUuid;
            }
            String areaRaw = queryParam(directUrl, "area");
            FileArea area = parseArea(areaRaw);
            try (InputStream in = sessionFileSystem.read(area, sessionUuid, path)) {
                byte[] bytes = in.readAllBytes();
                String name = ref.name() != null && !ref.name().isBlank()
                        ? ref.name()
                        : inferFileName(path);
                String mime = ref.mimeType() != null && !ref.mimeType().isBlank()
                        ? ref.mimeType()
                        : "application/octet-stream";
                return new OutboundAttachment(name, mime, bytes);
            } catch (Exception ex) {
                log.warn("Failed to read session attachment [path={}, session={}]: {}",
                        path, sessionUuid, ex.getMessage());
                return null;
            }
        }

        log.debug("Skipping outbound Slack attachment without session URL [refName={}]",
                ref.name());
        return null;
    }

    private static String normalizeSessionDownloadUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value;
        if (v.startsWith("session-url:")) {
            v = v.substring("session-url:".length());
        }
        return v.startsWith("/api/session-files/download?") ? v : null;
    }

    private static String queryParam(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return "";
        }
        String query = url.substring(q + 1);
        for (String token : query.split("&")) {
            String[] pair = token.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static FileArea parseArea(String areaRaw) {
        if (areaRaw == null || areaRaw.isBlank()) {
            return FileArea.SESSION;
        }
        try {
            return FileArea.valueOf(areaRaw.trim().toUpperCase());
        } catch (Exception ignored) {
            return FileArea.SESSION;
        }
    }

    private static String inferFileName(String path) {
        if (path == null || path.isBlank()) {
            return "attachment";
        }
        String normalized = path.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        String name = idx >= 0 ? normalized.substring(idx + 1) : normalized;
        return name.isBlank() ? "attachment" : name;
    }

    private record OutboundAttachment(String name, String mimeType, byte[] bytes) {}

    private String ingestAttachment(IncomingSlackMessage message,
                    String botToken,
                    String channelId,
                    String sessionUuid) {
        String fileUrl = message.fileUrl();
        String fileName = message.fileName() == null || message.fileName().isBlank()
            ? "slack-file"
            : message.fileName();

        byte[] bytes = slackApiClient.downloadFile(botToken, fileUrl);
        if (bytes == null || bytes.length == 0) {
            log.warn("Failed to download Slack file attachment [channel={}, url={}]", channelId, fileUrl);
            slackApiClient.sendMessage(botToken, channelId,
                "Sorry, I could not download your file. Please try again.");
            return null;
        }

        try {
            String targetPath = "incoming/slack/" + UUID.randomUUID() + "-" + sanitizeFileName(fileName);
            FileDescriptor descriptor = sessionFileSystem.write(
                    FileArea.SESSION,
                    sessionUuid,
                    targetPath,
                    new java.io.ByteArrayInputStream(bytes),
                    bytes.length);
            log.info("Slack file attached to chat turn [channel={}, session={}, path={}]",
                channelId, sessionUuid, descriptor.path());
            return "session-url:" + descriptor.downloadUrl();
        } catch (Exception ex) {
            log.warn("Failed to persist Slack attachment [channel={}, file={}]: {}",
                channelId, fileName, ex.getMessage());
            slackApiClient.sendMessage(botToken, channelId,
                "Sorry, I could not process your file. Please try again.");
            return null;
        }
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "attachment";
        }
        return fileName.replace("\\", "_").replace("/", "_");
    }

    /**
     * Downloads and transcribes the audio file attached to {@code message}.
     *
     * @return transcript text, or {@code null} if transcription could not be completed
     *         (an appropriate error message will have already been sent to the user)
     */
    private String transcribeVoice(IncomingSlackMessage message) {
        String channelId = message.channelId();
        String botToken  = message.botToken();
        String fileUrl   = message.voiceFileUrl();
        String mimeType  = message.voiceMimeType() != null ? message.voiceMimeType() : "audio/ogg";

        log.debug("ENTER transcribeVoice: [channel={}, mimeType={}]", channelId, mimeType);

        if (!audioTranscriptionService.isConfigured()) {
            log.warn("Voice note received but no transcription provider configured [channel={}]", channelId);
            slackApiClient.sendMessage(botToken, channelId,
                    "I received a voice note but no transcription provider is configured. Please send text instead.");
            return null;
        }

        byte[] audioBytes = slackApiClient.downloadFile(botToken, fileUrl);
        if (audioBytes == null || audioBytes.length == 0) {
            log.warn("Failed to download Slack audio file [channel={}, url={}]", channelId, fileUrl);
            slackApiClient.sendMessage(botToken, channelId,
                    "Sorry, I could not download your voice note. Please try again or send text.");
            return null;
        }

        try {
            AudioTranscriptionService.TranscriptionResult result =
                    audioTranscriptionService.transcribe(audioBytes, mimeType, "voice.ogg");
            String transcript = result.transcript();
            log.debug("EXIT transcribeVoice: [channel={}, transcriptLength={}]",
                    channelId, transcript == null ? 0 : transcript.length());
            return transcript;
        } catch (Exception e) {
            log.warn("Voice transcription failed [channel={}, error={}]", channelId, e.getMessage(), e);
            slackApiClient.sendMessage(botToken, channelId,
                    "Sorry, transcription failed. Please try again or send text instead.");
            return null;
        }
    }

    // ── Private: field capture ────────────────────────────────────────────────

    private void handleFieldCapture(PendingCapture capture, String fieldValue,
                                     String channelId, String botToken) {
        log.debug("Field capture [session={}, event={}, field={}]",
                capture.sessionUuid(), capture.eventId(), capture.fieldName());
        try {
            String result = resumptionService.resumeAndRun(
                    capture.username(), capture.sessionUuid(), capture.eventId(),
                    capture.actionName(), Map.of(capture.fieldName(), fieldValue));
            if (result != null && !result.isBlank()) {
                slackApiClient.sendMessage(botToken, channelId, result);
            }
        } catch (ToolSuspensionException ex) {
            renderLatestSuspension(capture.sessionUuid(), channelId, botToken);
        }
    }

    // ── Private: action choice ────────────────────────────────────────────────

    private void handleActionChoice(PendingActionChoice choice, String numberStr,
                                     String channelId, String botToken) {
        log.debug("Action choice [session={}, event={}, choice={}]",
                choice.sessionUuid(), choice.eventId(), numberStr);
        try {
            int idx = Integer.parseInt(numberStr) - 1;
            if (idx < 0 || idx >= choice.actions().size()) {
                slackApiClient.sendMessage(botToken, channelId,
                        "Please enter a number between 1 and " + choice.actions().size() + ".");
                // Re-store the pending choice
                pendingActions.put(channelId, choice);
                return;
            }
            String actionName = choice.actions().get(idx).name();
            String result = resumptionService.resumeAndRun(
                    choice.username(), choice.sessionUuid(), choice.eventId(),
                    actionName, Map.of());
            if (result != null && !result.isBlank()) {
                slackApiClient.sendMessage(botToken, channelId, result);
            }
        } catch (ToolSuspensionException ex) {
            renderLatestSuspension(choice.sessionUuid(), channelId, botToken);
        } catch (Exception e) {
            log.warn("Action choice error [session={}]: {}", choice.sessionUuid(), e.getMessage(), e);
            slackApiClient.sendMessage(botToken, channelId,
                    "An error occurred. Please try again.");
        }
    }

    // ── Private: suspension rendering ────────────────────────────────────────

    private void renderLatestSuspension(String sessionUuid, String channelId, String botToken) {
        AiSession session = sessionRepo.get(sessionUuid);
        if (session == null) return;

        UiEventFrame promptEvent = suspensionRenderer.findLatestPromptEvent(session);
        if (promptEvent == null) {
            log.warn("No PROMPT_REQUIRED message in suspended session [session={}]", sessionUuid);
            slackApiClient.sendMessage(botToken, channelId,
                    "An action is required. Please use the Vork web app.");
            return;
        }

        SlackSuspensionRenderer.FormClass formClass =
                suspensionRenderer.render(channelId, botToken, session, promptEvent);

        switch (formClass) {
            case SINGLE_TEXT -> {
                String fieldName = findSingleVisibleFieldName(promptEvent);
                String actionName = defaultSingleFieldAction(promptEvent);
                pendingCaptures.put(channelId,
                        new PendingCapture(session.username(), sessionUuid,
                        promptEvent.eventId(), fieldName, actionName));
            }
            case SIMPLE -> {
                List<FormAction> actions = suspensionRenderer.getActions(promptEvent.formSchema());
                if (!actions.isEmpty()) {
                    pendingActions.put(channelId,
                            new PendingActionChoice(session.username(), sessionUuid,
                                    promptEvent.eventId(), actions));
                }
            }
            case WEB_FORM -> { /* relay/self-hosted link sent; relay poll handles response */ }
        }
    }

    // ── Private: helpers ──────────────────────────────────────────────────────

    /**
     * Resolves the Vork username for a Slack {@code userId} by looking up
     * {@link UserNotificationMedia} records with {@code address=userId} and
     * {@code mediaType=SLACK}.
     *
     * @return username, or {@code null} if not found (sends a friendly DM to the user)
     */
    private String resolveUsername(String userId, String channelId, String botToken) {
        try (var stream = mediaRepo.search(0, 10, "createdAt", SortOrder.ASC,
                SearchQuery.eq("address", userId),
                SearchQuery.eq("mediaType", NotificationMediaType.SLACK.name()))) {

            return stream.map(UserNotificationMedia::userId).findFirst().orElseGet(() -> {
                log.warn("Slack userId {} not linked to any Vork account", userId);
                slackApiClient.sendMessage(botToken, channelId,
                        "Your Slack account isn't linked to Vork yet. "
                        + "Please register it from your Vork profile settings.");
                return null;
            });
        }
    }

    private String findSingleVisibleFieldName(UiEventFrame promptEvent) {
        if (promptEvent.formSchema() == null) return "value";
        if (promptEvent.formSchema().fields() == null) return "value";
        return promptEvent.formSchema().fields().stream()
                .filter(f -> f != null && !isInvisibleType(f.type()))
                .map(sh.vork.ai.protocol.interaction.FormField::name)
                .findFirst().orElse("value");
    }

    private static boolean isInvisibleType(String type) {
        if (type == null) return false;
        String t = type.toUpperCase();
        return "HIDDEN".equals(t) || "MARKDOWN".equals(t);
    }

    private String defaultSingleFieldAction(UiEventFrame promptEvent) {
        if (promptEvent.formSchema() == null || promptEvent.formSchema().actions() == null
                || promptEvent.formSchema().actions().isEmpty()) {
            return "ONCE";
        }
        String action = promptEvent.formSchema().actions().get(0).name();
        return (action == null || action.isBlank()) ? "ONCE" : action;
    }

    // ── Value types ───────────────────────────────────────────────────────────

    private record PendingCapture(String username, String sessionUuid,
                                   String eventId, String fieldName, String actionName) {}

    private record PendingActionChoice(String username, String sessionUuid,
                                        String eventId, List<FormAction> actions) {}
}
