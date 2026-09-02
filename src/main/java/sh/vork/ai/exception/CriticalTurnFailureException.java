package sh.vork.ai.exception;

/**
 * Signals a non-recoverable AI turn failure.
 *
 * <p>When this exception is thrown the current turn must be halted and surfaced
 * to the user. No automatic model fallback or prompt simplification should be
 * attempted for the same turn.
 */
public class CriticalTurnFailureException extends RuntimeException {

    public CriticalTurnFailureException(String message) {
        super(message);
    }

    public CriticalTurnFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}