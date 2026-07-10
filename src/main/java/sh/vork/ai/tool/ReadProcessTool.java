package sh.vork.ai.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.function.ReadProcessRequest;
import sh.vork.ai.process.ProcessContext;
import sh.vork.ai.process.ProcessManager;

@Component
public class ReadProcessTool extends AbstractProcessTool {

    private static final Logger log = LoggerFactory.getLogger(ReadProcessTool.class);

    private final ProcessManager processManager;

    public ReadProcessTool(ProcessManager processManager, ObjectMapper objectMapper) {
        super(objectMapper);
        this.processManager = processManager;
    }

    public String execute(ReadProcessRequest req) {
        log.debug("ENTER readProcess: reqPresent={}", req != null);
        if (req == null || req.pid() == null || req.pid().isBlank()) {
            return error("pid is required");
        }

        int timeout = req.timeoutSeconds() == null ? 0 : Math.max(0, req.timeoutSeconds());

        try {
            String sessionUuid = resolveSessionUuid();
            ProcessContext context = processManager.get(sessionUuid, req.pid());
            if (context == null) {
                return error("Unknown pid");
            }

            String output = context.drainOutput(timeout);
            boolean processActive = context.process().isAlive();

            Map<String, Object> payload = new LinkedHashMap<>();
            if (output.isEmpty()) {
                payload.put("status", "NO_NEW_OUTPUT");
                payload.put("process_active", processActive);
                log.debug("EXIT readProcess: no output [session={}, pid={}, active={}]", sessionUuid, req.pid(), processActive);
                return json(payload);
            }

            payload.put("status", "OK");
            payload.put("process_active", processActive);
            payload.put("output", output);
            log.debug("EXIT readProcess: drained [session={}, pid={}, chars={}]", sessionUuid, req.pid(), output.length());
            return json(payload);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return error("read interrupted");
        } catch (Exception ex) {
            log.warn("readProcess failed: {}", ex.getMessage());
            return error(ex.getMessage());
        }
    }
}
