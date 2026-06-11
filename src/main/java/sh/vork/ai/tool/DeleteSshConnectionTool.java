package sh.vork.ai.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import sh.vork.ai.function.DeleteSshConnectionRequest;
import sh.vork.ai.security.VisualizableTool;
import sh.vork.ssh.VirtualSshService;

@Component
public class DeleteSshConnectionTool implements VisualizableTool {

    private static final Logger log = LoggerFactory.getLogger(DeleteSshConnectionTool.class);

    private final VirtualSshService virtualSshService;
    private final ObjectMapper      objectMapper;

    public DeleteSshConnectionTool(VirtualSshService virtualSshService,
                                   ObjectMapper objectMapper) {
        this.virtualSshService = virtualSshService;
        this.objectMapper      = objectMapper;
    }

    public String execute(DeleteSshConnectionRequest req) {
        log.debug("ENTER DeleteSshConnectionTool.execute: hostOrAlias={}", req != null ? req.hostOrAlias() : null);
        if (req == null || req.hostOrAlias() == null || req.hostOrAlias().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"hostOrAlias is required\"}";
        }
        String sessionId   = resolveSessionUuid();
        String hostOrAlias = req.hostOrAlias().trim();
        try {
            int deleted = virtualSshService.deleteSshConnection(sessionId, hostOrAlias);
            String msg = deleted == 0
                    ? "No saved node found for '" + hostOrAlias + "' — nothing deleted."
                    : "SSH connection '" + hostOrAlias + "' deleted (" + deleted + " node record(s) removed).";
            log.debug("EXIT DeleteSshConnectionTool.execute: deleted={}", deleted);
            return "{\"status\":\"ok\",\"deleted\":" + deleted + ",\"message\":\"" + msg + "\"}";
        } catch (Exception e) {
            log.warn("deleteSshConnection failed [hostOrAlias={}]: {}", hostOrAlias, e.getMessage());
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    @Override
    public String formatAuthorizationDetails(String argumentsJson) {
        try {
            String host = objectMapper.readTree(argumentsJson).path("hostOrAlias").asText();
            return (host != null && !host.isBlank())
                    ? "Permanently delete SSH connection: " + host
                    : "Permanently delete SSH connection";
        } catch (Exception ex) {
            return "Permanently delete SSH connection";
        }
    }

    private String resolveSessionUuid() {
        String sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            throw new IllegalStateException("No sessionUuid available in execution context");
        }
        return sessionUuid;
    }
}
