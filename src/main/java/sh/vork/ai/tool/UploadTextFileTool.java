package sh.vork.ai.tool;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sshtools.client.sftp.SftpClient;

import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.function.UploadTextFileRequest;
import sh.vork.ai.security.VisualizableTool;
import sh.vork.ssh.VirtualSshService;

@Component
public class UploadTextFileTool implements VisualizableTool {

    private static final Logger log = LoggerFactory.getLogger(UploadTextFileTool.class);

    private final VirtualSshService virtualSshService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UploadTextFileTool(VirtualSshService virtualSshService) {
        this.virtualSshService = virtualSshService;
    }

    public String execute(UploadTextFileRequest req) {
        log.debug("ENTER UploadTextFileTool.execute: hostOrAlias={}, remotePath={}",
                req != null ? req.hostOrAlias() : null,
                req != null ? req.remotePath() : null);

        if (req == null || req.hostOrAlias() == null || req.hostOrAlias().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"hostOrAlias is required\"}";
        }
        if (req.remotePath() == null || req.remotePath().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"remotePath is required\"}";
        }
        if (req.content() == null) {
            return "{\"status\":\"error\",\"message\":\"content is required\"}";
        }

        String sessionId = resolveSessionUuid();
        String remotePath = req.remotePath().trim();
        byte[] bytes = req.content().getBytes(StandardCharsets.UTF_8);

        try {
            SftpClient sftp = virtualSshService.getSftpClient(sessionId, req.hostOrAlias());
            try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
                sftp.put(in, remotePath, null);
            }
            log.debug("EXIT UploadTextFileTool.execute: ok, remote={}, bytes={}", remotePath, bytes.length);
            return "{\"status\":\"ok\",\"remote\":\"" + remotePath + "\",\"bytes\":" + bytes.length + "}";
        } catch (ToolSuspensionException e) {
            throw e;
        } catch (Exception e) {
            log.warn("UploadTextFileTool failed: {}", e.getMessage(), e);
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private String resolveSessionUuid() {
        String sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            throw new IllegalStateException("No sessionUuid available in execution context");
        }
        return sessionUuid;
    }

    @Override
    public String formatAuthorizationDetails(String argumentsJson) {
                try {
                String content = objectMapper.readTree(argumentsJson)
                        .path("content")
                        .asText();
                if (content == null || content.isBlank()) {
                    return argumentsJson;
                }
                
                return StringUtils.abbreviate(content, 2000);
            } catch (Exception ex) {
                return argumentsJson;
            }
    }
}
