package sh.vork.ai.tool;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.sshtools.client.sftp.SftpClient;

import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.function.UploadFileRequest;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.ssh.VirtualSshService;

@Component
public class UploadFileTool {

    private final VirtualSshService virtualSshService;
    private final SessionFileSystem sessionFileSystem;

    public UploadFileTool(VirtualSshService virtualSshService,
                          SessionFileSystem sessionFileSystem) {
        this.virtualSshService = virtualSshService;
        this.sessionFileSystem = sessionFileSystem;
    }

    public String execute(UploadFileRequest req) {
        if (req == null || req.hostOrAlias() == null || req.hostOrAlias().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"hostOrAlias is required\"}";
        }
        if (req.filename() == null || req.filename().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"filename is required\"}";
        }

        String sessionId = resolveSessionUuid();
        String filename = req.filename().trim();

        SessionFileRef sessionRef = resolveSessionFileRef(sessionId, filename);

        if (sessionRef != null) {
            try {
                SftpClient sftp = virtualSshService.getSftpClient(sessionId, req.hostOrAlias());
                String remoteDest = resolveRemotePath(req.remotePath(), sessionRef.name());
                try (InputStream in = sessionFileSystem.read(sessionRef.area(), sessionRef.sessionUuid(), sessionRef.path())) {
                    sftp.put(in, remoteDest, null);
                }
                return "{\"status\":\"ok\",\"source\":\"session\",\"path\":\""
                        + json(sessionRef.path()) + "\",\"remote\":\"" + json(remoteDest) + "\"}";
            } catch (ToolSuspensionException e) {
                throw e;
            } catch (Exception e) {
                return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
            }
        }

        // Treat filename as a local filesystem path — requires authorization
        Object approval = ToolExecutionContext.get("UPLOAD_LOCAL_AUTHORIZED");
        if (!"true".equals(approval)) {
            throw localPathAuthorizationPrompt(filename);
        }

        try {
            SftpClient sftp = virtualSshService.getSftpClient(sessionId, req.hostOrAlias());
            java.io.File localFile = new java.io.File(filename);
            if (!localFile.exists() || !localFile.isFile()) {
                return "{\"status\":\"error\",\"message\":\"Local file not found: " + filename + "\"}";
            }
            String remoteDest = resolveRemotePath(req.remotePath(), localFile.getName());
            try (InputStream in = new java.io.FileInputStream(localFile)) {
                sftp.put(in, remoteDest, null);
            }
            return "{\"status\":\"ok\",\"source\":\"local\",\"path\":\"" + filename
                    + "\",\"remote\":\"" + remoteDest + "\"}";
        } catch (ToolSuspensionException e) {
            throw e;
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private SessionFileRef resolveSessionFileRef(String currentSessionUuid, String filename) {
        SessionFileRef parsed = parseSessionDownloadRef(filename, currentSessionUuid);
        if (parsed != null) {
            return parsed;
        }

        try {
            sessionFileSystem.read(FileArea.SESSION, currentSessionUuid, filename).close();
            return new SessionFileRef(FileArea.SESSION, currentSessionUuid, filename, extractFileName(filename));
        } catch (Exception ignored) {
            // fall through to local-path authorization flow
            return null;
        }
    }

    private static SessionFileRef parseSessionDownloadRef(String value, String defaultSessionUuid) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String raw = value;
        if (raw.startsWith("session-url:")) {
            raw = raw.substring("session-url:".length());
        }
        if (!raw.startsWith("/api/session-files/download?")) {
            return null;
        }
        String path = queryParam(raw, "path");
        if (path == null || path.isBlank()) {
            return null;
        }
        String areaRaw = queryParam(raw, "area");
        FileArea area = parseArea(areaRaw);
        String sessionUuid = queryParam(raw, "sessionUuid");
        if ((sessionUuid == null || sessionUuid.isBlank()) && area == FileArea.SESSION) {
            sessionUuid = defaultSessionUuid;
        }
        return new SessionFileRef(area, area == FileArea.SESSION ? sessionUuid : null, path, extractFileName(path));
    }

    private static String queryParam(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0 || q == url.length() - 1) {
            return "";
        }
        String query = url.substring(q + 1);
        for (String token : query.split("&")) {
            String[] pair = token.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static FileArea parseArea(String areaRaw) {
        if (areaRaw == null || areaRaw.isBlank()) {
            return FileArea.SESSION;
        }
        try {
            return FileArea.valueOf(areaRaw.trim().toUpperCase());
        } catch (Exception ignored) {
            return FileArea.SESSION;
        }
    }

    private static String extractFileName(String path) {
        if (path == null || path.isBlank()) {
            return "file";
        }
        String normalized = path.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        String name = idx >= 0 ? normalized.substring(idx + 1) : normalized;
        return name.isBlank() ? "file" : name;
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String resolveRemotePath(String remotePath, String localName) {
        if (remotePath == null || remotePath.isBlank()) {
            return localName;
        }
        String rp = remotePath.trim();
        // If it looks like a directory path (ends with /), append the filename
        if (rp.endsWith("/")) {
            return rp + localName;
        }
        return rp;
    }

    private static ToolSuspensionException localPathAuthorizationPrompt(String localPath) {
        InteractionFormSchema schema = new InteractionFormSchema(
                "AUTHORIZE_TOOL",
                "Local File Upload Authorization",
                "Authorize reading from the local filesystem path and uploading to the remote host: " + localPath,
                List.of(new FormField(
                        "UPLOAD_LOCAL_AUTHORIZED",
                        "CHECKBOX",
                        "Authorize local file upload",
                        "I authorize reading this file from the local filesystem and uploading it.",
                        true,
                        FieldSource.CONTEXT,
                        Collections.emptyList())),
                List.of(
                        new FormAction("ONCE", "Authorize", "warning"),
                        new FormAction("DENIED", "Cancel", "danger")));
        return new ToolSuspensionException("sshUploadFile", "{}",
                "Authorization required to read file from local filesystem.", schema);
    }

    private String resolveSessionUuid() {
        String sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            throw new IllegalStateException("No sessionUuid available in execution context");
        }
        return sessionUuid;
    }

    private record SessionFileRef(FileArea area, String sessionUuid, String path, String name) {}
}
