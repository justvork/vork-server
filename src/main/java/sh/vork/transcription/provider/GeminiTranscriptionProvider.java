package sh.vork.transcription.provider;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import sh.vork.ai.AiProvider;
import sh.vork.ai.provider.AiChatClientFactory;
import sh.vork.notification.SettingDefinition;
import sh.vork.transcription.TranscriptionException;
import sh.vork.transcription.TranscriptionProvider;

/**
 * Transcription provider backed by Google Gemini's multimodal capability.
 *
 * <p>Credentials are read from the auto-configured Gemini {@code ChatClient} managed
 * by {@link AiChatClientFactory}.  No additional settings are required.
 *
 * <p>Audio bytes are passed directly to the model as a {@code Media} object alongside
 * a transcription-only system prompt.  This is a separate, isolated call — no chat
 * history or session state is involved.
 */
@Component
public class GeminiTranscriptionProvider implements TranscriptionProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiTranscriptionProvider.class);

    private static final String TRANSCRIPTION_PROMPT =
            "Transcribe this audio message. Output only the transcript text with no " +
            "commentary, labels, or additional content.";

    private final AiChatClientFactory chatClientFactory;

    public GeminiTranscriptionProvider(AiChatClientFactory chatClientFactory) {
        this.chatClientFactory = chatClientFactory;
    }

    @Override
    public String providerKey() {
        return "gemini";
    }

    @Override
    public String displayName() {
        return "Gemini (multimodal)";
    }

    @Override
    public String backedByAiProvider() {
        return AiProvider.GEMINI.name();
    }

    @Override
    public List<SettingDefinition> getSettingDefinitions() {
        return List.of();
    }

    @Override
    public Map<String, String> validate(Map<String, String> settings) {
        return Map.of();
    }

    @Override
    public String transcribe(byte[] audioBytes, String mimeType, Map<String, String> settings) {
        log.debug("ENTER transcribe: [provider=gemini, mimeType={}, bytes={}]", mimeType, audioBytes.length);

        ChatClient base = chatClientFactory.getBaseClient(AiProvider.GEMINI);
        if (base == null) {
            throw new TranscriptionException("Gemini ChatClient is not available");
        }

        MimeType resolvedMimeType = resolveMimeType(mimeType);

        try {
            ByteArrayResource audioResource = new ByteArrayResource(audioBytes);

            String transcript = base.mutate().build()
                    .prompt()
                    .user(u -> u.text(TRANSCRIPTION_PROMPT)
                                .media(resolvedMimeType, audioResource))
                    .call()
                    .content();

            log.debug("EXIT transcribe: [provider=gemini, transcriptLength={}]",
                    transcript == null ? 0 : transcript.length());
            return transcript;
        } catch (Exception e) {
            log.warn("Gemini transcription failed: {}", e.getMessage(), e);
            throw new TranscriptionException("Gemini transcription failed: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static MimeType resolveMimeType(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return MimeType.valueOf("audio/ogg");
        }
        // Strip codec suffix if present (e.g. "audio/ogg; codecs=opus" -> "audio/ogg")
        int semi = mimeType.indexOf(';');
        String base = semi >= 0 ? mimeType.substring(0, semi).trim() : mimeType.trim();
        try {
            return MimeType.valueOf(base);
        } catch (Exception e) {
            log.warn("Unrecognised audio MIME type '{}', falling back to audio/ogg", mimeType);
            return MimeType.valueOf("audio/ogg");
        }
    }
}
