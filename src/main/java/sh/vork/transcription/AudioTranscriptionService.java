package sh.vork.transcription;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.FileDescriptor;
import sh.vork.filesystem.SessionFileSystem;

import java.util.UUID;

/**
 * Orchestrates audio transcription: stores the raw audio file via
 * {@link SessionFileSystem} and delegates transcription to the active
 * {@link TranscriptionProvider}.
 *
 * <p>The raw audio is stored regardless of whether transcription succeeds so
 * that the original voice note is always preserved in the session attachment
 * list for transparency.
 */
@Service
public class AudioTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(AudioTranscriptionService.class);

    private final SessionFileSystem sessionFileSystem;
    private final TranscriptionConfigService transcriptionConfigService;

    public AudioTranscriptionService(SessionFileSystem sessionFileSystem,
                                     TranscriptionConfigService transcriptionConfigService) {
        this.sessionFileSystem       = sessionFileSystem;
        this.transcriptionConfigService = transcriptionConfigService;
    }

    /**
     * Transcribes {@code audioBytes} and returns the transcript together with the UUID
     * of the stored audio file.
     *
     * @param audioBytes raw audio data
     * @param mimeType   MIME type of the audio (e.g. {@code "audio/ogg"})
     * @param filename   original filename hint (e.g. {@code "voice.ogg"})
     * @return result containing the transcript text and the stored file UUID
     * @throws TranscriptionException   if transcription fails or no provider is configured
     * @throws IllegalStateException    if the audio file cannot be stored
     */
    public TranscriptionResult transcribe(byte[] audioBytes, String mimeType, String filename) {
        log.debug("ENTER transcribe: [mimeType={}, bytes={}, filename={}]",
                mimeType, audioBytes.length, filename);

        // Store the audio file first so it is always reachable from the session
        FileDescriptor storedFile = storeAudio(audioBytes, mimeType, filename);

        // Resolve active transcription provider
        TranscriptionProvider provider = transcriptionConfigService.resolveProvider();
        if (provider == null) {
            throw new TranscriptionException("No transcription provider is configured");
        }

        TranscriptionConfig cfg = transcriptionConfigService.getCurrent();
        String transcript = provider.transcribe(
                audioBytes,
                mimeType,
                cfg != null ? cfg.settings() : java.util.Map.of());

        log.debug("EXIT transcribe: [storedFileUuid={}, transcriptLength={}]",
                storedFile.downloadUrl(), transcript == null ? 0 : transcript.length());

            return new TranscriptionResult(transcript, storedFile.downloadUrl());
    }

    /**
     * Returns {@code true} when a transcription provider is configured and ready.
     */
    public boolean isConfigured() {
        return transcriptionConfigService.resolveProvider() != null;
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    /**
     * Result of a successful transcription.
     *
     * @param transcript    plain-text transcript of the audio
    * @param storedFileUuid download URL of the persisted audio file
     */
    public record TranscriptionResult(String transcript, String storedFileUuid) {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FileDescriptor storeAudio(byte[] audioBytes, String mimeType, String filename) {
        try {
            String safeName = (filename != null && !filename.isBlank()) ? filename : "voice_note";
            String path = "transcription/" + UUID.randomUUID() + "-" + safeName;
            return sessionFileSystem.write(
                    FileArea.SHARED,
                    null,
                    path,
                    new ByteArrayInputStream(audioBytes),
                    audioBytes.length);
        } catch (IOException e) {
            log.error("Failed to store audio file: {}", e.getMessage(), e);
            throw new IllegalStateException("Could not store audio file", e);
        }
    }
}
