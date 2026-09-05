package sh.vork.filesystem;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import sh.vork.storage.AiMimeTypeSupport;

import java.io.IOException;
import java.io.PushbackInputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Raw streaming upload endpoint that bypasses Spring multipart buffering/limits.
 */
public class SessionUploadStreamingServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(SessionUploadStreamingServlet.class);

    private final SessionFileSystem sessionFileSystem;
    private final SessionFileAuthorizationService authorizationService;
    private final ObjectMapper objectMapper;

    public SessionUploadStreamingServlet(SessionFileSystem sessionFileSystem,
                                         SessionFileAuthorizationService authorizationService,
                                         ObjectMapper objectMapper) {
        this.sessionFileSystem = sessionFileSystem;
        this.authorizationService = authorizationService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String rawArea = request.getParameter("area");
        FileArea area = parseArea(rawArea);
        String sessionUuid = request.getParameter("sessionUuid");
        String path = resolveTargetPath(request);
        Principal principal = request.getUserPrincipal();

        log.debug("ENTER upload-stream: area={}, sessionUuid={}, path={}, user={}",
                area, sessionUuid, path, principal == null ? null : principal.getName());

        if (!authorizationService.isAuthorized(area, sessionUuid, principal)) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN, Map.of("status", "error", "message", "Access denied"));
            return;
        }
        if (path == null || path.isBlank()) {
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST, Map.of("status", "error", "message", "path is required"));
            return;
        }

        long sizeHint = request.getContentLengthLong();
        String mimeType = request.getContentType();
        if (mimeType == null || mimeType.isBlank()) {
            mimeType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }

        try (PushbackInputStream in = new PushbackInputStream(request.getInputStream(), 1)) {
            int first = in.read();
            if (first < 0) {
                writeJson(response, HttpServletResponse.SC_BAD_REQUEST, Map.of("status", "error", "message", "Uploaded file is empty"));
                return;
            }
            in.unread(first);

            FileDescriptor descriptor = sessionFileSystem.write(area, sessionUuid, path, in, sizeHint);
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

            log.debug("EXIT upload-stream: area={}, sessionUuid={}, path={}, size={}",
                    descriptor.area(), descriptor.sessionUuid(), descriptor.path(), descriptor.sizeBytes());
            writeJson(response, HttpServletResponse.SC_OK, payload);
        } catch (Exception ex) {
            log.warn("Failed upload-stream [area={}, session={}, path={}]: {}", area, sessionUuid, path, ex.getMessage());
            String message = ex.getMessage();
            if (message == null || message.isBlank()) {
                message = "Upload failed due to an unexpected server error";
            }
            writeJson(response, HttpServletResponse.SC_BAD_REQUEST, Map.of("status", "error", "message", message));
        }
    }

    private static FileArea parseArea(String rawArea) {
        if (rawArea == null || rawArea.isBlank()) {
            return FileArea.SESSION;
        }
        try {
            return FileArea.valueOf(rawArea.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return FileArea.SESSION;
        }
    }

    private static String resolveTargetPath(HttpServletRequest request) {
        String fromParam = request.getParameter("path");
        if (fromParam != null && !fromParam.isBlank()) {
            return fromParam;
        }
        String fromHeader = request.getHeader("X-File-Path");
        if (fromHeader != null && !fromHeader.isBlank()) {
            return fromHeader;
        }
        String fromName = request.getHeader("X-File-Name");
        if (fromName != null && !fromName.isBlank()) {
            return fromName;
        }
        return null;
    }

    private void writeJson(HttpServletResponse response, int status, Map<String, ?> payload) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(payload));
    }

    private static String fileName(String relativePath) {
        String normalized = relativePath.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }
}
