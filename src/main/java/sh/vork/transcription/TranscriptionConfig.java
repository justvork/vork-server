package sh.vork.transcription;

import java.util.Map;

import com.jadaptive.orm.DatabaseEntity;

/**
 * Singleton configuration for the active audio transcription provider.
 *
 * <p>Exactly one instance is stored in MongoDB under the fixed primary key
 * {@code "current"}.  Absence of this document means no transcription provider
 * has been configured.
 *
 * <p>For AI-backed providers (Gemini, OpenAI, Groq) {@code settings} is always
 * empty — credentials are read from {@link sh.vork.ai.provider.AiProviderConfig}.
 * The {@code settings} field is reserved for future standalone providers that
 * manage their own API keys.
 */
public record TranscriptionConfig(
        String uuid,                   // always "current"
        String providerKey,            // e.g. "openai", "groq", "gemini"
        Map<String, String> settings   // provider-specific overrides; empty for AI-backed providers
) implements DatabaseEntity {}
