package sh.vork.ai.tool;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.sshtools.client.sftp.SftpClient;

import sh.vork.ai.exception.ToolSuspensionException;
import sh.vork.ai.function.DownloadFileRequest;
import sh.vork.ai.protocol.interaction.FieldSource;
import sh.vork.ai.protocol.interaction.FormAction;
import sh.vork.ai.protocol.interaction.FormField;
import sh.vork.ai.protocol.interaction.InteractionFormSchema;
import sh.vork.ai.context.ToolExecutionContext;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.FileDescriptor;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.ssh.VirtualSshService;

@Component
public class DownloadFileTool {

    private final VirtualSshService virtualSshService;
    private final SessionFileSystem sessionFileSystem;
    private static final String GENERATED_ATTACHMENTS_CONTEXT_KEY = "generated.session.attachments";

    public DownloadFileTool(VirtualSshService virtualSshService,
                            SessionFileSystem sessionFileSystem) {
        this.virtualSshService = virtualSshService;
        this.sessionFileSystem = sessionFileSystem;
    }

    public String execute(DownloadFileRequest req) {
        if (req == null || req.hostOrAlias() == null || req.hostOrAlias().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"hostOrAlias is required\"}";
        }
        if (req.remotePath() == null || req.remotePath().isBlank()) {
            return "{\"status\":\"error\",\"message\":\"remotePath is required\"}";
        }

        String sessionId = resolveSessionUuid();
        boolean hasLocalPath = req.localPath() != null && !req.localPath().isBlank();

        if (hasLocalPath) {
            // Check if the user has authorised writing to the local filesystem
            Object approval = sh.vork.ai.context.ToolExecutionContext.get("DOWNLOAD_LOCAL_AUTHORIZED");
            if (!"true".equals(approval)) {
                throw localPathAuthorizationPrompt(req.localPath());
            }
        }

        try {
            SftpClient sftp = virtualSshService.getSftpClient(sessionId, req.hostOrAlias());
            String filename = extractFilename(req.remotePath());

            if (hasLocalPath) {
                // Save to local filesystem (authorization already confirmed above)
                File localFile = new File(req.localPath());
                File parent = localFile.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                try (FileOutputStream out = new FileOutputStream(localFile)) {
                    sftp.get(req.remotePath(), out);
                }
                return "{\"status\":\"ok\",\"location\":\"local\",\"path\":\"" + localFile.getAbsolutePath() + "\"}";
            } else {
                // Save to session file system
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                sftp.get(req.remotePath(), buffer);
                byte[] bytes = buffer.toByteArray();
                String relativePath = "downloads/" + UUID.randomUUID() + "-" + sanitizeFileName(filename);
                FileDescriptor descriptor = sessionFileSystem.write(
                        FileArea.SESSION,
                        sessionId,
                        relativePath,
                        new java.io.ByteArrayInputStream(bytes),
                        bytes.length);
                recordGeneratedAttachment(descriptor.path(), "application/octet-stream", descriptor.downloadUrl());
                return "{\"status\":\"ok\",\"location\":\"session\",\"path\":\"" + descriptor.path()
                        + "\",\"name\":\"" + extractFilename(descriptor.path()) + "\","
                        + "\"size\":" + descriptor.sizeBytes() + ","
                        + "\"downloadUrl\":\"" + descriptor.downloadUrl() + "\","
                        + "\"attachmentRef\":\"session-url:" + descriptor.downloadUrl() + "\"}";
            }
        } catch (ToolSuspensionException e) {
            throw e;
        } catch (Exception e) {
            return "{\"status\":\"error\",\"message\":\"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    private static String extractFilename(String remotePath) {
        int idx = Math.max(remotePath.lastIndexOf('/'), remotePath.lastIndexOf('\\'));
        return idx >= 0 ? remotePath.substring(idx + 1) : remotePath;
    }

    private static ToolSuspensionException localPathAuthorizationPrompt(String localPath) {
        InteractionFormSchema schema = new InteractionFormSchema(
                "AUTHORIZE_TOOL",
                "Local File Download Authorization",
                "Authorize saving the downloaded file to the local filesystem at: " + localPath,
                List.of(new FormField(
                        "DOWNLOAD_LOCAL_AUTHORIZED",
                        "CHECKBOX",
                        "Authorize local file download",
                        "I authorize saving this file to the local filesystem.",
                        true,
                        FieldSource.CONTEXT,
                        Collections.emptyList())),
                List.of(
                        new FormAction("ONCE", "Authorize", "warning"),
                        new FormAction("DENIED", "Cancel", "danger")));
        return new ToolSuspensionException("sshDownloadFile", "{}",
                "Authorization required to save file to local filesystem.", schema);
    }

    private String resolveSessionUuid() {
        String sessionUuid = MDC.get("sessionUuid");
        if (sessionUuid == null || sessionUuid.isBlank() || "<null>".equals(sessionUuid)) {
            throw new IllegalStateException("No sessionUuid available in execution context");
        }
        return sessionUuid;
    }

    private static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "download";
        }
        return fileName.replace("\\", "_").replace("/", "_");
    }

    @SuppressWarnings("unchecked")
    private static void recordGeneratedAttachment(String path, String mimeType, String downloadUrl) {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            return;
        }
        Object existing = ToolExecutionContext.get(GENERATED_ATTACHMENTS_CONTEXT_KEY);
        List<Map<String, String>> items;
        if (existing instanceof List<?> list) {
            items = (List<Map<String, String>>) list;
        } else {
            items = new ArrayList<>();
            ToolExecutionContext.put(GENERATED_ATTACHMENTS_CONTEXT_KEY, items);
        }

        Map<String, String> entry = new LinkedHashMap<>();
        entry.put("path", path == null ? "" : path);
        entry.put("mimeType", mimeType == null ? "application/octet-stream" : mimeType);
        entry.put("downloadUrl", downloadUrl);
        items.add(entry);
    }
}
