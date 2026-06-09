package sh.vork.transcription.provider;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiAudioTranscriptionModel;
import org.springframework.ai.openai.OpenAiAudioTranscriptionOptions;
import org.springframework.ai.openai.api.OpenAiAudioApi;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;

import sh.vork.ai.AiProvider;
import sh.vork.ai.provider.AiProviderConfig;
import sh.vork.ai.provider.AiProviderConfigService;
import sh.vork.notification.SettingDefinition;
import sh.vork.transcription.TranscriptionException;
import sh.vork.transcription.TranscriptionProvider;

/**
 * Transcription provider backed by OpenAI Whisper ({@code whisper-1}).
 *
 * <p>Credentials are read from the persisted {@link AiProviderConfig} for
 * {@link AiProvider#OPENAI}.  No additional settings are required.
 */
@Component
public class OpenAiTranscriptionProvider implements TranscriptionProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTranscriptionProvider.class);

    private final AiProviderConfigService configService;

    public OpenAiTranscriptionProvider(AiProviderConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String providerKey() {
        return "openai";
    }

    @Override
    public String displayName() {
        return "OpenAI Whisper";
    }

    @Override
    public String backedByAiProvider() {
        return AiProvider.OPENAI.name();
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
        log.debug("ENTER transcribe: [provider=openai, mimeType={}, bytes={}]", mimeType, audioBytes.length);

        AiProviderConfig cfg = configService.getConfig(AiProvider.OPENAI);
        if (cfg == null || !cfg.enabled()) {
            throw new TranscriptionException("OpenAI provider is not configured or disabled");
        }
        String apiKey = configService.decryptApiKey(cfg.apiKey());
        if (apiKey == null || apiKey.isBlank()) {
            throw new TranscriptionException("OpenAI API key is missing");
        }

        try {
            OpenAiAudioApi audioApi = OpenAiAudioApi.builder()
                    .apiKey(apiKey)
                    .build();

            OpenAiAudioTranscriptionOptions options = OpenAiAudioTranscriptionOptions.builder()
                    .model("whisper-1")
                    .build();

            OpenAiAudioTranscriptionModel model = new OpenAiAudioTranscriptionModel(audioApi, options);

            String filename = filenameForMimeType(mimeType);
            ByteArrayResource resource = namedResource(audioBytes, filename);

            String transcript = model.call(resource);
            log.debug("EXIT transcribe: [provider=openai, transcriptLength={}]",
                    transcript == null ? 0 : transcript.length());
            return transcript;
        } catch (TranscriptionException te) {
            throw te;
        } catch (Exception e) {
            log.warn("OpenAI transcription failed: {}", e.getMessage(), e);
            throw new TranscriptionException("OpenAI transcription failed: " + e.getMessage(), e);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static String filenameForMimeType(String mimeType) {
        if (mimeType == null) return "audio.bin";
        return switch (mimeType) {
            case "audio/ogg", "audio/ogg; codecs=opus" -> "audio.ogg";
            case "audio/mp4", "audio/m4a"              -> "audio.m4a";
            case "audio/mpeg", "audio/mp3"             -> "audio.mp3";
            case "audio/wav", "audio/wave"             -> "audio.wav";
            case "audio/webm"                          -> "audio.webm";
            default -> "audio.bin";
        };
    }

    static ByteArrayResource namedResource(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }
}
