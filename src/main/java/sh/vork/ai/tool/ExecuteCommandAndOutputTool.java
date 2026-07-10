package sh.vork.ai.tool;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.function.ExecuteCommandAndOutputRequest;
import sh.vork.ai.process.ShellCommandProcessBuilder;

@Component
public class ExecuteCommandAndOutputTool extends AbstractProcessTool {

    private static final Logger log = LoggerFactory.getLogger(ExecuteCommandAndOutputTool.class);
    private static final int TIMEOUT_SECONDS = 60;

    public ExecuteCommandAndOutputTool(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    public String execute(ExecuteCommandAndOutputRequest req) {
        log.debug("ENTER executeCommandAndOutput: reqPresent={}", req != null);
        if (req == null || req.command() == null || req.command().isBlank()) {
            return error("command is required");
        }

        try {
            ProcessBuilder builder = ShellCommandProcessBuilder.from(req.command());
            builder.redirectErrorStream(true);
            Process process = builder.start();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Thread reader = startReader(process.getInputStream(), output);

            boolean completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
            }

            reader.join(TimeUnit.SECONDS.toMillis(2));
            int exitCode = completed ? process.exitValue() : -1;

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("exit_code", exitCode);
            payload.put("output", output.toString(StandardCharsets.UTF_8));
            if (!completed) {
                payload.put("timed_out", true);
            }
            log.debug("EXIT executeCommandAndOutput: exitCode={}, timedOut={}", exitCode, !completed);
            return json(payload);
        } catch (Exception ex) {
            log.warn("executeCommandAndOutput failed: {}", ex.getMessage());
            return error(ex.getMessage());
        }
    }

    private static Thread startReader(InputStream stream, ByteArrayOutputStream output) {
        Thread reader = Thread.ofPlatform()
                .name("sync-command-reader")
                .daemon(true)
                .start(() -> {
                    byte[] buffer = new byte[1024];
                    try {
                        int read;
                        while ((read = stream.read(buffer)) != -1) {
                            if (read > 0) {
                                synchronized (output) {
                                    output.write(buffer, 0, read);
                                }
                            }
                        }
                    } catch (IOException ignored) {
                        // Ignore stream closure while process is terminating.
                    }
                });
        return reader;
    }
}
