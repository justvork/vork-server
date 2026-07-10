package sh.vork.ai.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.function.StopProcessRequest;
import sh.vork.ai.process.ProcessContext;
import sh.vork.ai.process.ProcessManager;

@Component
public class StopProcessTool extends AbstractProcessTool {

    private static final Logger log = LoggerFactory.getLogger(StopProcessTool.class);

    private final ProcessManager processManager;

    public StopProcessTool(ProcessManager processManager, ObjectMapper objectMapper) {
        super(objectMapper);
        this.processManager = processManager;
    }

    public String execute(StopProcessRequest req) {
        log.debug("ENTER stopProcess: reqPresent={}", req != null);
        if (req == null || req.pid() == null || req.pid().isBlank()) {
            return error("pid is required");
        }

        String sessionUuid = resolveSessionUuid();
        ProcessContext context = processManager.get(sessionUuid, req.pid());
        if (context == null) {
            return error("Unknown pid");
        }

        processManager.stop(sessionUuid, req.pid());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "TERMINATED");
        log.debug("EXIT stopProcess: session={}, pid={}", sessionUuid, req.pid());
        return json(payload);
    }
}
