package sh.vork.filesystem;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.multipart.MultipartFile;
import sh.vork.ai.entity.AiSession;
import sh.vork.orm.DatabaseRepository;
import sh.vork.storage.AiMimeTypeSupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Session/shared file browsing and download endpoints.
 */
@RestController
@RequestMapping("/api/session-files")
public class SessionFileController {

    private static final Logger log = LoggerFactory.getLogger(SessionFileController.class);

    private final SessionFileSystem sessionFileSystem;
    private final DatabaseRepository<AiSession> aiSessionRepository;

    public SessionFileController(SessionFileSystem sessionFileSystem,
                                 DatabaseRepository<AiSession> aiSessionRepository) {
        this.sessionFileSystem = sessionFileSystem;
        this.aiSessionRepository = aiSessionRepository;
    }

    @GetMapping("/list")
    public ResponseEntity<?> list(@RequestParam FileArea area,
                                  @RequestParam(required = false) String sessionUuid,
                                  @RequestParam(required = false) String dir,
                                  Principal principal) {
        log.debug("ENTER list: area={}, sessionUuid={}, dir={}, user={}",
            area, sessionUuid, dir, principal == null ? null : principal.getName());
        if (!isAuthorized(area, sessionUuid, principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "error", "message", "Access denied"));
        }

        try {
            List<FileNode> items = sessionFileSystem.list(area, sessionUuid, dir);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("area", area.name());
            payload.put("sessionUuid", area == FileArea.SESSION ? sessionUuid : null);
            payload.put("dir", dir == null ? "" : dir);
            payload.put("items", items);
            log.debug("EXIT list: area={}, sessionUuid={}, count={}", area, sessionUuid, items.size());
            return ResponseEntity.ok(payload);
        } catch (Exception ex) {
            log.warn("Failed to list files [area={}, session={}, dir={}]: {}", area, sessionUuid, dir, ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @GetMapping("/download")
    public void download(@RequestParam FileArea area,
                         @RequestParam(required = false) String sessionUuid,
                         @RequestParam String path,
                         Principal principal,
                         HttpServletResponse response) throws IOException {
        log.debug("ENTER download: area={}, sessionUuid={}, path={}, user={}",
                area, sessionUuid, path, principal == null ? null : principal.getName());
        if (!isAuthorized(area, sessionUuid, principal)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Access denied");
            return;
        }

        try (InputStream in = sessionFileSystem.read(area, sessionUuid, path)) {
            String name = fileName(path);
            String mime = probeMimeType(name);
            response.setContentType(mime);
            boolean inline = mime.startsWith("image/");
            String disposition = inline
                ? "inline; filename=\"" + escapeHeaderValue(name) + "\""
                : "attachment; filename=\"" + escapeHeaderValue(name) + "\"";
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition);
            StreamUtils.copy(in, response.getOutputStream());
            log.debug("EXIT download: area={}, sessionUuid={}, path={}", area, sessionUuid, path);
        } catch (Exception ex) {
            log.warn("Failed to download file [area={}, session={}, path={}]: {}", area, sessionUuid, path, ex.getMessage());
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST, ex.getMessage());
            }
        }
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam FileArea area,
                                    @RequestParam(required = false) String sessionUuid,
                                    @RequestParam(value = "path", required = false) String path,
                                    @RequestParam("file") MultipartFile file,
                                    Principal principal) {
        log.debug("ENTER upload: area={}, sessionUuid={}, path={}, user={}",
            area, sessionUuid, path, principal == null ? null : principal.getName());
        if (!isAuthorized(area, sessionUuid, principal)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "error", "message", "Access denied"));
        }
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "Uploaded file is empty"));
        }

        String targetPath = (path == null || path.isBlank()) ? file.getOriginalFilename() : path;
        String mimeType = (file.getContentType() == null || file.getContentType().isBlank())
            ? MediaType.APPLICATION_OCTET_STREAM_VALUE
            : file.getContentType();
        try (InputStream in = file.getInputStream()) {
            FileDescriptor descriptor = sessionFileSystem.write(area, sessionUuid, targetPath, in, file.getSize());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", "ok");
            payload.put("area", descriptor.area().name());
            payload.put("sessionUuid", descriptor.sessionUuid());
            payload.put("path", descriptor.path());
            payload.put("name", fileName(descriptor.path()));
            payload.put("mimeType", mimeType);
            payload.put("aiSupported", AiMimeTypeSupport.isAiSupported(mimeType));
            payload.put("sizeBytes", descriptor.sizeBytes());
            payload.put("downloadUrl", descriptor.downloadUrl());
            log.debug("EXIT upload: area={}, sessionUuid={}, path={}, size={}",
                descriptor.area(), descriptor.sessionUuid(), descriptor.path(), descriptor.sizeBytes());
            return ResponseEntity.ok(payload);
        } catch (Exception ex) {
            log.warn("Failed to upload session file [area={}, session={}, path={}]: {}", area, sessionUuid, targetPath, ex.getMessage());
            String message = ex.getMessage();
            if (message == null || message.isBlank()) {
                message = "Upload failed due to an unexpected server error";
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("status", "error", "message", message));
        }
    }

    private boolean isAuthorized(FileArea area, String sessionUuid, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return false;
        }
        if (area == FileArea.SHARED) {
            return true;
        }
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return false;
        }

        AiSession session = aiSessionRepository.get(sessionUuid);
        return session != null && principal.getName().equals(session.username());
    }

    private static String fileName(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    private static String probeMimeType(String filename) {
        try {
            String probed = Files.probeContentType(Path.of(filename));
            if (probed != null && !probed.isBlank()) {
                return probed;
            }
        } catch (Exception ignored) {
            // Fallback below.
        }
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".json")
                || lower.endsWith(".yaml") || lower.endsWith(".yml") || lower.endsWith(".csv")
                || lower.endsWith(".log")) {
            return "text/plain";
        }
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private static String escapeHeaderValue(String value) {
        return value == null ? "" : value.replace("\"", "'").replace("\n", "").replace("\r", "");
    }
}
