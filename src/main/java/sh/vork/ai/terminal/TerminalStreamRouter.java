package sh.vork.ai.terminal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.sshtools.client.SessionChannelNG;
import com.sshtools.client.SshClient;
import com.sshtools.client.shell.ExpectShell;
import com.sshtools.client.shell.ShellProcess;
import com.sshtools.client.shell.ExpectShell.ExpectShellBuilder;
import com.sshtools.common.ssh.SshException;
import com.sshtools.synergy.ssh.TerminalModes;
import com.sshtools.synergy.ssh.TerminalModes.TerminalModesBuilder;

import sh.vork.ai.entity.SessionOriginMode;
import sh.vork.ai.websocket.TerminalBinarySocketRegistry;
import sh.vork.ssh.VirtualSshService;

@Service
public class TerminalStreamRouter {

    private static final Logger log = LoggerFactory.getLogger(TerminalStreamRouter.class);
    private static final int BUFFER_SIZE = 1024;
    private static final int TOOL_INLINE_OUTPUT_MAX_CHARS = 24_000;
    private static final java.util.regex.Pattern ANSI_ESCAPE_PATTERN = java.util.regex.Pattern.compile("\\u001B\\[[0-9;?]*[ -/]*[@-~]");

    private final VirtualSshService virtualSshService;
    private final SimpMessagingTemplate messagingTemplate;
    private final TerminalOutputStore terminalOutputStore;
    private final TerminalBinarySocketRegistry terminalBinarySocketRegistry;

    private final Map<String, ActiveTerminalSession> sessionsByCompositeKey = new ConcurrentHashMap<>();
    private final Map<String, ActiveTerminalSession> sessionsBySessionUuid = new ConcurrentHashMap<>();

    @Autowired
    public TerminalStreamRouter(VirtualSshService virtualSshService,
                                SimpMessagingTemplate messagingTemplate,
                                TerminalOutputStore terminalOutputStore,
                                TerminalBinarySocketRegistry terminalBinarySocketRegistry) {
        this.virtualSshService = virtualSshService;
        this.messagingTemplate = messagingTemplate;
        this.terminalOutputStore = terminalOutputStore;
        this.terminalBinarySocketRegistry = terminalBinarySocketRegistry;
    }

    public TerminalStreamRouter(VirtualSshService virtualSshService,
                                SimpMessagingTemplate messagingTemplate) {
        this(virtualSshService, messagingTemplate, null, null);
    }

    public String executeStreamedCommand(String sessionUuid,
                                         String host,
                                         String command,
                                         SessionOriginMode originMode) {
        ActiveTerminalSession session = getOrCreateShell(sessionUuid, host);
        TerminalOutputStore.TerminalOutputWriter outputWriter = terminalOutputStore == null
            ? null
            : terminalOutputStore.createWriter(sessionUuid, command);
        StringBuilder fallbackOutput = outputWriter == null ? new StringBuilder() : null;
        StringBuilder inlineToolOutput = new StringBuilder();
        String terminalId = UUID.randomUUID().toString();
        ShellProcess process;

        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/chat/" + sessionUuid,
                    Map.of("type", "EVENT", "status", "TERMINAL_START", "command", command, "terminalId", terminalId));
        }

        synchronized (session.lock()) {
            try {
                process = session.shell().executeCommand(command, false);
                session.setActiveProcess(process, terminalId);
                session.setActiveInput(process.getOutputStream());
                session.clearAborted();
            } catch (SshException | IOException ex) {
                throw new IllegalStateException("Failed to start terminal command stream", ex);
            }
        }
        
        // Stream stats for instrumentation
        long streamStartTime = System.currentTimeMillis();
        long frameCount = 0;
        long totalBytes = 0;
        boolean wasAborted = false;

        try (InputStream stdout = process.getInputStream()) {
            // Store reference so terminateActiveCommand() can close it and
            // unblock ExpectShell's read() when Ctrl+C doesn't produce the
            // expected end-marker (shell drops back to prompt instead).
            synchronized (session.lock()) {
                session.setActiveStream(stdout);
            }
            byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                int bytesRead = stdout.read(buffer);
                if (bytesRead == -1) {
                    break;
                }

                byte[] payload = Arrays.copyOf(buffer, bytesRead);
                String chunk = new String(payload, StandardCharsets.UTF_8);

                frameCount++;
                totalBytes += bytesRead;

                if (outputWriter != null) {
                    try {
                        outputWriter.write(chunk);
                        outputWriter.flush();
                    } catch (IOException ex) {
                        log.warn("Failed to write terminal output to file: {}", ex.getMessage());
                    }
                } else {
                    fallbackOutput.append(chunk);
                }

                appendLimited(inlineToolOutput, chunk, TOOL_INLINE_OUTPUT_MAX_CHARS);

                if (terminalBinarySocketRegistry != null) {
                    terminalBinarySocketRegistry.broadcast(sessionUuid, terminalId, payload);
                }

                if (!process.isActive() && stdout.available() == 0) {
                    break;
                }
            }
            process.waitFor();
            
            // Final stream stats
            long elapsedMs = System.currentTimeMillis() - streamStartTime;
            long avgFrameSize = frameCount > 0 ? totalBytes / frameCount : 0;
            long throughputKbps = elapsedMs > 0 ? (totalBytes * 8) / elapsedMs : 0;
            log.info("Shell stream COMPLETED [session={}, terminal={}]: frames={}, totalBytes={}, avgSize={} bytes, throughput={}Kbps, elapsed={}ms",
                    sessionUuid, terminalId, frameCount, totalBytes, avgFrameSize, throughputKbps, elapsedMs);
        } catch (IOException ex) {
            // If this IOException was caused by terminateActiveCommand() closing the
            // stream to unblock ExpectShell, swallow it silently — wasAborted handles it.
            if (!session.wasAborted()) {
                // Stream broken mid-read (e.g. SSH disconnect). Log and continue so that
                // TERMINAL_END is still broadcast and the UI is not left in a locked state.
                log.warn("Terminal stream broken (connection lost?) [session={}, terminal={}]: {}",
                        sessionUuid, terminalId, ex.getMessage());
                appendLimited(inlineToolOutput, "\n[connection lost: " + ex.getMessage() + "]", TOOL_INLINE_OUTPUT_MAX_CHARS);
            } else {
                log.debug("Terminal stream closed due to user abort [session={}, terminal={}]",
                        sessionUuid, terminalId);
            }
        } finally {
            synchronized (session.lock()) {
                wasAborted = session.wasAborted();
                session.clearActiveProcess();
            }
        }

        // Finalize output file and get the stored file UUID
        String outputFileUuid = null;
        if (terminalOutputStore != null && outputWriter != null) {
            try {
                sh.vork.filesystem.FileDescriptor storedFile = terminalOutputStore.finalize(outputWriter);
                if (storedFile != null) {
                    outputFileUuid = storedFile.downloadUrl();
                    log.info("Terminal output file stored [command={}, path={}]", command, storedFile.path());
                }
            } catch (Exception ex) {
                log.error("Failed to finalize terminal output file: {}", ex.getMessage(), ex);
            }
        }

        if (messagingTemplate != null) {
            messagingTemplate.convertAndSend("/topic/chat/" + sessionUuid,
                    Map.of(
                            "type", "EVENT",
                            "status", wasAborted ? "TERMINAL_ABORTED" : "TERMINAL_END",
                            "terminalId", terminalId));
        }

        // Return JSON response with file UUID (if available) for tool response
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("status", wasAborted ? "ABORTED" : "COMPLETED");
        response.put("command", command);
        response.put("terminalId", terminalId);
        if (outputFileUuid != null) {
            response.put("outputFileUuid", outputFileUuid);
        }
        if (fallbackOutput != null) {
            response.put("output", sanitizeForModel(fallbackOutput.toString()));
        } else {
            response.put("output", sanitizeForModel(inlineToolOutput.toString()));
        }

        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response);
        } catch (Exception ex) {
            log.error("Failed to serialize terminal response: {}", ex.getMessage());
            return sanitizeForModel("");
        }
    }

    /**
     * Sends {@code Ctrl+C} (byte 0x03) to the stdin of the currently running command
     * and flags the session as aborted.  The I/O loop will detect the flag when the
     * process exits and throw {@link AbortedTerminalCommandException}.
     *
     * @return {@code true} if the signal was delivered; {@code false} if no command
     *         is currently running or the {@code terminalId} does not match.
     */
    public boolean terminateActiveCommand(String sessionUuid, String terminalId) {
        ActiveTerminalSession session = sessionsBySessionUuid.get(sessionUuid);
        if (session == null) {
            log.debug("terminateActiveCommand: no active session [session={}]", sessionUuid);
            return false;
        }
        synchronized (session.lock()) {
            if (terminalId != null && !terminalId.isBlank()
                    && !terminalId.equals(session.activeTerminalId())) {
                log.warn("terminateActiveCommand: terminalId mismatch, ignoring "
                        + "[session={}, requested={}, active={}]",
                        sessionUuid, terminalId, session.activeTerminalId());
                return false;
            }
            OutputStream stdin = session.activeInput();
            if (stdin == null) {
                log.debug("terminateActiveCommand: no active stdin [session={}]", sessionUuid);
                return false;
            }
            try {
                stdin.write(0x03); // Ctrl+C
                stdin.flush();
                session.markAborted();
                log.info("terminateActiveCommand: Ctrl+C sent [session={}, terminal={}]",
                        sessionUuid, terminalId);
                // Close the stdout stream to forcibly unblock ExpectShell's read() —
                // Ctrl+C drops the subprocess but the shell stays alive, so ExpectShell
                // never sees its end-marker and read() would hang indefinitely.
                // InputStream activeStream = session.activeStream();
                // if (activeStream != null) {
                //     try {
                //         activeStream.close();
                //     } catch (IOException closeEx) {
                //         log.debug("terminateActiveCommand: stream close (expected) [session={}]: {}",
                //                 sessionUuid, closeEx.getMessage());
                //     }
                // }
                return true;
            } catch (IOException ex) {
                log.warn("terminateActiveCommand: failed to send Ctrl+C [session={}]: {}",
                        sessionUuid, ex.getMessage());
                return false;
            }
        }
    }

    public void writeInput(String sessionUuid, byte[] payload) {
        writeInput(sessionUuid, null, payload);
    }

    public void writeInput(String sessionUuid, String terminalId, byte[] payload) {
        if (payload == null || payload.length == 0) {
            return;
        }
        ActiveTerminalSession session = sessionsBySessionUuid.get(sessionUuid);
        if (session == null) {
            log.debug("Ignoring terminal input because no active session was found [session={}]", sessionUuid);
            return;
        }

        synchronized (session.lock()) {
            if (terminalId != null && !terminalId.isBlank() && !terminalId.equals(session.activeTerminalId())) {
                log.debug("Ignoring terminal input because the command is no longer active [session={}, terminalId={}]",
                        sessionUuid, terminalId);
                return;
            }
            OutputStream in = session.activeInput();
            if (in == null) {
                log.debug("Ignoring terminal input because no active command stdin is available [session={}]", sessionUuid);
                return;
            }
            try {
                in.write(payload);
                in.flush();
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to write terminal input", ex);
            }
        }
    }

    /**
     * Removes a session from the router maps after an abort so the next command
     * gets a fresh {@link ExpectShell} whose state machine is in sync with the
     * actual shell.  Best-effort close of the underlying SSH channel and client.
     */
    @SuppressWarnings("unused")
    private void invalidateSession(ActiveTerminalSession session) {
        String key = session.sessionUuid() + "|" + session.host();
        sessionsByCompositeKey.remove(key, session);
        sessionsBySessionUuid.remove(session.sessionUuid(), session);
        log.info("Terminal session invalidated after abort — next command will use a fresh shell "
                + "[session={}, host={}]", session.sessionUuid(), session.host());
        try {
            if (session.channel() != null) {
                session.channel().close();
            }
        } catch (Exception ex) {
            log.debug("Channel close during session invalidation [session={}]: {}",
                    session.sessionUuid(), ex.getMessage());
        }
        try {
            if (session.client() != null) {
                session.client().close();
            }
        } catch (Exception ex) {
            log.debug("Client close during session invalidation [session={}]: {}",
                    session.sessionUuid(), ex.getMessage());
        }
    }

    private ActiveTerminalSession getOrCreateShell(String sessionUuid, String host) {        String normalizedHost = (host == null || host.isBlank()) ? "localhost" : host.trim();
        String key = sessionUuid + "|" + normalizedHost;

        ActiveTerminalSession existing = sessionsByCompositeKey.get(key);
        if (existing != null && !existing.isClosed()) {
            sessionsBySessionUuid.put(sessionUuid, existing);
            return existing;
        }

        ActiveTerminalSession created = createShellSession(sessionUuid, normalizedHost);
        sessionsByCompositeKey.put(key, created);
        sessionsBySessionUuid.put(sessionUuid, created);
        return created;
    }

    static String sanitizeForModel(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }

        String sanitized = ANSI_ESCAPE_PATTERN.matcher(output).replaceAll("");
        sanitized = sanitized.replace('\u0000', ' ');
        sanitized = sanitized.replaceAll("\\r\\n", "\n");
        sanitized = sanitized.replace('\r', '\n');
        sanitized = sanitized.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        sanitized = sanitized.trim();

        if (sanitized.isBlank() && !output.isBlank()) {
            return "[terminal output omitted: control characters only]";
        }
        return sanitized;
    }

    static void appendLimited(StringBuilder target, String chunk, int maxChars) {
        if (target == null || chunk == null || chunk.isEmpty() || maxChars <= 0) {
            return;
        }
        if (target.length() >= maxChars) {
            return;
        }
        int remaining = maxChars - target.length();
        if (chunk.length() <= remaining) {
            target.append(chunk);
        } else {
            target.append(chunk, 0, remaining);
        }
    }

    static String selectUiOutput(String command, String output) {
        String normalized = normalizeUiOutput(command, output);
        if (normalized.isBlank()) {
            return "";
        }
        return normalized;
    }

    static boolean hasDisplayableContent(String chunk) {
        if (chunk == null || chunk.isBlank()) {
            return false;
        }
        String cleaned = ANSI_ESCAPE_PATTERN.matcher(chunk).replaceAll("");
        cleaned = cleaned.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        return !cleaned.trim().isBlank();
    }

    static String normalizeUiOutput(String command, String output) {
        String normalized = output == null ? "" : output;
        if (normalized.isBlank()) {
            return "";
        }

        normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');

        if (command != null && !command.isBlank()) {
            while (!normalized.isBlank()) {
                int newlineIndex = normalized.indexOf('\n');
                String firstLine = newlineIndex >= 0 ? normalized.substring(0, newlineIndex) : normalized;
                String firstLineWithoutPrompt = firstLine.replaceFirst("^\\s*[$#>%]\\s*", "").trim();

                if (!firstLineWithoutPrompt.contains(command)) {
                    break;
                }

                if (newlineIndex < 0) {
                    return "";
                }
                normalized = normalized.substring(newlineIndex + 1);
            }
        }

        return normalized;
    }

    private TerminalModes createTerminalModes() {
        return TerminalModesBuilder.create()
        .withMode(TerminalModes.Mode.TTY_OP_OSPEED, 9600)
        .withMode(TerminalModes.Mode.TTY_OP_ISPEED, 9600)
        .withMode(TerminalModes.Mode.VINTR, 3)
        .withMode(TerminalModes.Mode.VQUIT, 28)
        .withMode(TerminalModes.Mode.VERASE, 127)
        .withMode(TerminalModes.Mode.VKILL, 21)
        .withMode(TerminalModes.Mode.VEOF, 4)
        .withMode(TerminalModes.Mode.VEOL, 255)
        .withMode(TerminalModes.Mode.VEOL2, 255)
        .withMode(TerminalModes.Mode.VSTART, 17)
        .withMode(TerminalModes.Mode.VSTOP, 19)
        .withMode(TerminalModes.Mode.VSUSP, 26)
        .withMode(TerminalModes.Mode.VDSUSP, 25)
        .withMode(TerminalModes.Mode.VREPRINT, 18)
        .withMode(TerminalModes.Mode.VWERASE, 23)
        .withMode(TerminalModes.Mode.VLNEXT, 22)
        .withMode(TerminalModes.Mode.VSTATUS, 20)
        .withMode(TerminalModes.Mode.VDISCARD, 15)
        .withMode(TerminalModes.Mode.IGNPAR, 0)
        .withMode(TerminalModes.Mode.PARMRK, 0)
        .withMode(TerminalModes.Mode.INPCK, 0)
        .withMode(TerminalModes.Mode.ISTRIP, 0)
        .withMode(TerminalModes.Mode.INLCR, 0)
        .withMode(TerminalModes.Mode.ICRNL, 1)
        .withMode(TerminalModes.Mode.IXON, 1)
        .withMode(TerminalModes.Mode.IXANY, 1)
        .withMode(TerminalModes.Mode.IXOFF, 0)
        .withMode(TerminalModes.Mode.IMAXBEL, 1)
        .withMode(TerminalModes.Mode.IUTF8, 1)
        .withMode(TerminalModes.Mode.ISIG, 1)
        .withMode(TerminalModes.Mode.ICANON, 1)
        .withMode(TerminalModes.Mode.ECHO, 1)
        .withMode(TerminalModes.Mode.ECHOE, 1)
        .withMode(TerminalModes.Mode.ECHOK, 0)
        .withMode(TerminalModes.Mode.ECHONL, 0)
        .withMode(TerminalModes.Mode.NOFLSH, 0)
        .withMode(TerminalModes.Mode.TOSTOP, 0)
        .withMode(TerminalModes.Mode.IEXTEN, 1)
        .withMode(TerminalModes.Mode.ECHOCTL, 1)
        .withMode(TerminalModes.Mode.ECHOKE, 1)
        .withMode(TerminalModes.Mode.PENDIN, 1)
        .withMode(TerminalModes.Mode.OPOST, 1)
        .withMode(TerminalModes.Mode.ONLCR, 1)
        .withMode(TerminalModes.Mode.OCRNL, 0)
        .withMode(TerminalModes.Mode.ONOCR, 0)
        .withMode(TerminalModes.Mode.ONLRET, 0)
        .withMode(TerminalModes.Mode.CS7, 1)
        .withMode(TerminalModes.Mode.CS8, 1)
        .withMode(TerminalModes.Mode.PARENB, 0)
        .withMode(TerminalModes.Mode.PARODD, 0)
        .build();

    }
    private ActiveTerminalSession createShellSession(String sessionUuid, String host) {
        try {
            SshClient client = createClient(host, 10);
            SessionChannelNG channel = client.openSessionChannel();
            channel.allocatePseudoTerminal("xterm-256color", 
                120, 
                40,
                createTerminalModes())
            .waitFor(Duration.ofSeconds(5));
            channel.startShell();

            ExpectShell shell = ExpectShellBuilder
                .create()
                .withSession(channel)
                .build();
            log.info("Created terminal stream shell [session={}, host={}]", sessionUuid, host);
            return new ActiveTerminalSession(sessionUuid, host, client, channel, shell);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to initialize terminal shell session", ex);
        }
    }

    private SshClient createClient(String host, int timeout) throws IOException, SshException, InterruptedException {
        String target = (host == null || host.isBlank()) ? "local" : host.trim();
        if ("local".equalsIgnoreCase(target)
                || "localhost".equalsIgnoreCase(target)
                || "127.0.0.1".equals(target)) {
            return virtualSshService.connectLocal(timeout);
        }

        String sshUser = null;
        String sshHost = target;
        int at = target.indexOf('@');
        if (at > 0 && at < target.length() - 1) {
            sshUser = target.substring(0, at).trim();
            sshHost = target.substring(at + 1).trim();
        }

        return virtualSshService.connectClient(sshUser, sshHost, timeout);
    }

    private record ActiveTerminalSession(
            String sessionUuid,
            String host,
            SshClient client,
            SessionChannelNG channel,
            ExpectShell shell,
            Object lock,
            ActiveProcessRef activeProcessRef
    ) {
        private ActiveTerminalSession(String sessionUuid,
                                      String host,
                                      SshClient client,
                                      SessionChannelNG channel,
                                      ExpectShell shell) {
            this(sessionUuid, host, client, channel, shell, new Object(), new ActiveProcessRef());
        }

        private void setActiveProcess(ShellProcess process, String terminalId) {
            activeProcessRef.terminalId = terminalId;
            activeProcessRef.process = process;
        }

        private void setActiveInput(OutputStream input) {
            activeProcessRef.stdin = input;
        }

        private OutputStream activeInput() {
            return activeProcessRef.stdin;
        }

        private String activeTerminalId() {
            return activeProcessRef.terminalId;
        }

        private void setActiveStream(InputStream stream) {
            activeProcessRef.activeStream = stream;
        }

        @SuppressWarnings("unused")
        private InputStream activeStream() {
            return activeProcessRef.activeStream;
        }

        private void clearActiveProcess() {
            activeProcessRef.stdin = null;
            activeProcessRef.terminalId = null;
            activeProcessRef.process = null;
            activeProcessRef.activeStream = null;
            activeProcessRef.aborted = false;
        }

        private void clearAborted() {
            activeProcessRef.aborted = false;
        }

        private void markAborted() {
            activeProcessRef.aborted = true;
        }

        private boolean wasAborted() {
            return activeProcessRef.aborted;
        }

        private boolean isClosed() {
            return shell == null || shell.isClosed() || client == null || !client.isConnected();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof ActiveTerminalSession other)) {
                return false;
            }
            return Objects.equals(sessionUuid, other.sessionUuid)
                    && Objects.equals(host, other.host);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sessionUuid, host);
        }
    }

    private static final class ActiveProcessRef {
        private OutputStream stdin;
        private String terminalId;
        @SuppressWarnings("unused")
        private ShellProcess process;
        private InputStream activeStream;
        private volatile boolean aborted;
    }
}
