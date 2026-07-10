package sh.vork.ai.process;

import java.util.List;

/**
 * Creates a shell-backed process builder suitable for local command execution.
 */
public final class ShellCommandProcessBuilder {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(java.util.Locale.ROOT)
            .contains("win");

    private ShellCommandProcessBuilder() {
    }

    public static ProcessBuilder from(String command) {
        if (WINDOWS) {
            return new ProcessBuilder(List.of("cmd.exe", "/c", command));
        }
        return new ProcessBuilder(List.of("/bin/sh", "-lc", command));
    }
}
