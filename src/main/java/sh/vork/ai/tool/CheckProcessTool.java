package sh.vork.ai.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.function.CheckProcessRequest;
import sh.vork.ai.process.ProcessContext;
import sh.vork.ai.process.ProcessManager;

@Component
public class CheckProcessTool extends AbstractProcessTool {

    private static final Logger log = LoggerFactory.getLogger(CheckProcessTool.class);

    private final ProcessManager processManager;

    public CheckProcessTool(ProcessManager processManager, ObjectMapper objectMapper) {
        super(objectMapper);
        this.processManager = processManager;
    }

    public String execute(CheckProcessRequest req) {
        log.debug("ENTER checkProcess: reqPresent={}", req != null);
        if (req == null || req.pid() == null || req.pid().isBlank()) {
            return error("pid is required");
        }

        String sessionUuid = resolveSessionUuid();
        ProcessContext context = processManager.get(sessionUuid, req.pid());
        if (context == null) {
            return error("Unknown pid");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (context.process().isAlive()) {
            payload.put("status", "RUNNING");
        } else {
            payload.put("status", "EXITED");
            payload.put("exit_code", context.process().exitValue());
        }
        log.debug("EXIT checkProcess: session={}, pid={}, status={}", sessionUuid, req.pid(), payload.get("status"));
        return json(payload);
    }
}
