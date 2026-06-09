package sh.vork.transcription;

/**
 * Thrown when an audio transcription attempt fails.
 */
public class TranscriptionException extends RuntimeException {

    public TranscriptionException(String message) {
        super(message);
    }

    public TranscriptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
