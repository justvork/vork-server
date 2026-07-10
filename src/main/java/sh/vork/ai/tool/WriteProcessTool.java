package sh.vork.ai.tool;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import sh.vork.ai.function.WriteProcessRequest;
import sh.vork.ai.process.ProcessContext;
import sh.vork.ai.process.ProcessManager;

@Component
public class WriteProcessTool extends AbstractProcessTool {

    private static final Logger log = LoggerFactory.getLogger(WriteProcessTool.class);

    private final ProcessManager processManager;

    public WriteProcessTool(ProcessManager processManager, ObjectMapper objectMapper) {
        super(objectMapper);
        this.processManager = processManager;
    }

    public String execute(WriteProcessRequest req) {
        log.debug("ENTER writeProcess: reqPresent={}", req != null);
        if (req == null || req.pid() == null || req.pid().isBlank()) {
            return error("pid is required");
        }
        if (req.input() == null) {
            return error("input is required");
        }

        try {
            String sessionUuid = resolveSessionUuid();
            ProcessContext context = processManager.get(sessionUuid, req.pid());
            if (context == null) {
                return error("Unknown pid");
            }

            String input = req.input().contains("\n") ? req.input() : req.input() + "\n";
            context.appendInput(input);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "WRITTEN");
            log.debug("EXIT writeProcess: session={}, pid={}, bytes={}", sessionUuid, req.pid(), input.length());
            return json(payload);
        } catch (Exception ex) {
            log.warn("writeProcess failed: {}", ex.getMessage());
            return error(ex.getMessage());
        }
    }
}
