package sh.vork.ai.terminal;

/**
 * Thrown by {@link TerminalStreamRouter#executeStreamedCommand} when the user
 * explicitly terminates a running command via the UI stop action.  The exception
 * propagates through the tool-call mechanism so the AI model receives a clear
 * signal that the command was aborted by the user — not that it failed.
 */
public class AbortedTerminalCommandException extends RuntimeException {

    public AbortedTerminalCommandException(String command) {
        super("Command was terminated by the user: " + command);
    }
}
