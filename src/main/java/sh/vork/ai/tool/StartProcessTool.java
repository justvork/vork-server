package sh.vork.ai.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.function.StartProcessRequest;
import sh.vork.ai.process.ProcessManager;

@Component
public class StartProcessTool extends AbstractProcessTool {

    private static final Logger log = LoggerFactory.getLogger(StartProcessTool.class);

    private final ProcessManager processManager;

    public StartProcessTool(ProcessManager processManager, ObjectMapper objectMapper) {
        super(objectMapper);
        this.processManager = processManager;
    }

    public String execute(StartProcessRequest req) {
        log.debug("ENTER startProcess: reqPresent={}", req != null);
        if (req == null || req.command() == null || req.command().isBlank()) {
            return error("command is required");
        }

        try {
            String sessionUuid = resolveSessionUuid();
            String pid = processManager.start(sessionUuid, req.command());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "STARTED");
            payload.put("pid", pid);
            log.debug("EXIT startProcess: session={}, pid={}", sessionUuid, pid);
            return json(payload);
        } catch (Exception ex) {
            log.warn("startProcess failed: {}", ex.getMessage());
            return error(ex.getMessage());
        }
    }
}
