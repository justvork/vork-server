package sh.vork.ai.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import sh.vork.ai.function.CreateSessionTextFileRequest;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.FileDescriptor;
import sh.vork.filesystem.SessionFileSystem;

/**
 * Creates a UTF-8 text file in the current session sandbox or shared area.
 */
@Component
public class CreateSessionTextFileTool {

    private static final Logger log = LoggerFactory.getLogger(CreateSessionTextFileTool.class);

    private final SessionFileSystem sessionFileSystem;

    public CreateSessionTextFileTool(SessionFileSystem sessionFileSystem) {
        this.sessionFileSystem = sessionFileSystem;
    }

    public String execute(CreateSessionTextFileRequest req) {
        log.debug("ENTER execute: area={}, path={}", req == null ? null : req.area(), req == null ? null : req.path());
        if (req == null || req.path() == null || req.path().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"path is required\"}";
        }
        if (req.content() == null) {
            return "{\"status\":\"error\",\"message\":\"content is required\"}";
        }

        FileArea area = parseArea(req.area());
        String sessionUuid = resolveSessionUuid();
        String owner = area == FileArea.SESSION ? sessionUuid : null;

        try {
            FileDescriptor descriptor = sessionFileSystem.writeText(area, owner, req.path(), req.content());
            log.debug("EXIT execute: area={}, session={}, path={}, size={}",
                    descriptor.area(), descriptor.sessionUuid(), descriptor.path(), descriptor.sizeBytes());
            return "{\"status\":\"ok\","
                    + "\"area\":\"" + descriptor.area().name() + "\"," 
                    + "\"path\":\"" + json(descriptor.path()) + "\"," 
                    + "\"name\":\"" + json(extractFileName(descriptor.path())) + "\"," 
                    + "\"size\":" + descriptor.sizeBytes() + ","
                    + "\"downloadUrl\":\"" + json(descriptor.downloadUrl()) + "\"}";
        } catch (Exception ex) {
            log.warn("createSessionTextFile failed: {}", ex.getMessage());
            return "{\"status\":\"error\",\"message\":\"" + json(ex.getMessage()) + "\"}";
        }
    }

    private static FileArea parseArea(String rawArea) {
        if (rawArea == null || rawArea.isBlank()) {
            return FileArea.SESSION;
        }
        try {
            return FileArea.valueOf(rawArea.trim().toUpperCase());
        } catch (Exception ignored) {
            return FileArea.SESSION;
        }
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static String extractFileName(String path) {
        if (path == null || path.isBlank()) {
            return "generated-file";
        }
        String normalized = path.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        String name = idx >= 0 ? normalized.substring(idx + 1) : normalized;
        return name.isBlank() ? "generated-file" : name;
    }

    private static String resolveSessionUuid() {
        String sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            throw new IllegalStateException("No sessionUuid available in execution context");
        }
        return sessionUuid;
    }
}
