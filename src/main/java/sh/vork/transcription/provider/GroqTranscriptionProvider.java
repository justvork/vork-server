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
 * Transcription provider backed by Groq's hosted Whisper ({@code whisper-large-v3-turbo}).
 *
 * <p>Groq's API is OpenAI-compatible.  Credentials are read from the persisted
 * {@link AiProviderConfig} for {@link AiProvider#GROQ}.  No additional settings
 * are required — the same API key used for Groq chat completions is reused here.
 */
@Component
public class GroqTranscriptionProvider implements TranscriptionProvider {

    private static final Logger log = LoggerFactory.getLogger(GroqTranscriptionProvider.class);
    private static final String GROQ_BASE_URL = "https://api.groq.com/openai/v1";
    private static final String TRANSCRIPTION_MODEL = "whisper-large-v3-turbo";

    private final AiProviderConfigService configService;

    public GroqTranscriptionProvider(AiProviderConfigService configService) {
        this.configService = configService;
    }

    @Override
    public String providerKey() {
        return "groq";
    }

    @Override
    public String displayName() {
        return "Groq Whisper";
    }

    @Override
    public String backedByAiProvider() {
        return AiProvider.GROQ.name();
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
        log.debug("ENTER transcribe: [provider=groq, mimeType={}, bytes={}]", mimeType, audioBytes.length);

        AiProviderConfig cfg = configService.getConfig(AiProvider.GROQ);
        if (cfg == null || !cfg.enabled()) {
            throw new TranscriptionException("Groq provider is not configured or disabled");
        }
        String apiKey = configService.decryptApiKey(cfg.apiKey());
        if (apiKey == null || apiKey.isBlank()) {
            throw new TranscriptionException("Groq API key is missing");
        }

        try {
            OpenAiAudioApi audioApi = OpenAiAudioApi.builder()
                    .apiKey(apiKey)
                    .baseUrl(GROQ_BASE_URL)
                    .build();

            OpenAiAudioTranscriptionOptions options = OpenAiAudioTranscriptionOptions.builder()
                    .model(TRANSCRIPTION_MODEL)
                    .build();

            OpenAiAudioTranscriptionModel model = new OpenAiAudioTranscriptionModel(audioApi, options);

            String filename = OpenAiTranscriptionProvider.filenameForMimeType(mimeType);
            ByteArrayResource resource = OpenAiTranscriptionProvider.namedResource(audioBytes, filename);

            String transcript = model.call(resource);
            log.debug("EXIT transcribe: [provider=groq, transcriptLength={}]",
                    transcript == null ? 0 : transcript.length());
            return transcript;
        } catch (TranscriptionException te) {
            throw te;
        } catch (Exception e) {
            log.warn("Groq transcription failed: {}", e.getMessage(), e);
            throw new TranscriptionException("Groq transcription failed: " + e.getMessage(), e);
        }
    }
}
