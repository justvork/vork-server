package sh.vork.transcription;

import java.util.List;
import java.util.Map;

import sh.vork.notification.SettingDefinition;

/**
 * SPI for pluggable audio transcription backends.
 *
 * <p>Implementations are discovered as Spring beans.  Each implementation is identified
 * by a unique {@link #providerKey()} and declares whether it requires its own credentials
 * (standalone) or piggy-backs on an existing AI provider configuration
 * (see {@link #backedByAiProvider()}).
 *
 * <p>Example implementations: {@code OpenAiTranscriptionProvider},
 * {@code GroqTranscriptionProvider}, {@code GeminiTranscriptionProvider}.
 */
public interface TranscriptionProvider {

    /**
     * Stable machine identifier for this provider (e.g. {@code "openai"}, {@code "groq"}).
     * Must be unique across all registered implementations.
     */
    String providerKey();

    /**
     * Human-readable name shown in the settings UI (e.g. {@code "OpenAI Whisper"}).
     */
    String displayName();

    /**
     * The {@link sh.vork.ai.AiProvider} enum name that backs this transcription provider,
     * or {@code null} if this is a standalone provider with its own credentials.
     *
     * <p>When non-null the UI renders a checkbox on the corresponding AI provider card
     * rather than a separate configuration form.  Credentials are read from the matching
     * {@link sh.vork.ai.provider.AiProviderConfig}.
     */
    default String backedByAiProvider() {
        return null;
    }

    /**
     * Configuration fields required by this provider.  Empty for AI-backed providers
     * that reuse an existing {@code AiProviderConfig}.
     */
    List<SettingDefinition> getSettingDefinitions();

    /**
     * Validates the supplied settings map.
     *
     * @return a map of {@code fieldKey → error message}; empty map means valid
     */
    Map<String, String> validate(Map<String, String> settings);

    /**
     * Transcribes the supplied audio bytes and returns the transcript as plain text.
     *
     * @param audioBytes raw audio data (OGG, MP4, WAV, MP3, etc.)
     * @param mimeType   MIME type of the audio (e.g. {@code "audio/ogg"})
     * @param settings   provider-specific settings from {@link TranscriptionConfig#settings()}
     * @return the transcript; never {@code null}
     * @throws TranscriptionException if transcription fails
     */
    String transcribe(byte[] audioBytes, String mimeType, Map<String, String> settings);
}
