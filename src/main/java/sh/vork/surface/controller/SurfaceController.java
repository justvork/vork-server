package sh.vork.surface.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import sh.vork.ai.entity.AiSession;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.surface.Surface;
import sh.vork.surface.service.SurfaceService;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Page and REST API controller for the Surfaces management UI and Surface Editor.
 */
@Controller
public class SurfaceController {

    private static final Logger log = LoggerFactory.getLogger(SurfaceController.class);

    private final SurfaceService surfaceService;
    private final SessionFileSystem sessionFileSystem;

    public SurfaceController(SurfaceService surfaceService,
                             SessionFileSystem sessionFileSystem) {
        this.surfaceService = surfaceService;
        this.sessionFileSystem = sessionFileSystem;
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    @GetMapping("/surfaces")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public String surfacesPage(Model model) {
        log.debug("ENTER surfacesPage");
        model.addAttribute("surfaces", surfaceService.list());
        return "surfaces";
    }

    @GetMapping("/surfaces/{uuid}/editor")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public String surfaceEditorPage(@PathVariable String uuid, Model model) {
        log.debug("ENTER surfaceEditorPage: [uuid={}]", uuid);
        model.addAttribute("surfaceUuid", uuid);
        return "surface-editor";
    }

    @GetMapping("/surface/{uuid}/preview")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public String previewSurfaceIndex(@PathVariable String uuid) {
        return "redirect:/surface/" + uuid + "/preview/index.html";
    }

    @GetMapping("/surface/{uuid}/preview/{*path}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    @ResponseBody
    public ResponseEntity<?> previewSurfaceFile(@PathVariable String uuid,
                                                @PathVariable String path,
                                                Principal principal) {
        log.debug("ENTER previewSurfaceFile: [surfaceUuid={}, path={}, user={}]",
                uuid, path, principal == null ? null : principal.getName());
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Access denied");
        }

        try {
            AiSession session = surfaceService.ensureSession(uuid, principal.getName());
            String relativePath = (path == null || path.isBlank()) ? "index.html" : path;
            try (InputStream in = sessionFileSystem.read(FileArea.SESSION, session.uuid(), relativePath)) {
                byte[] bytes = in.readAllBytes();
                String mime = probeMimeType(relativePath);
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(mime))
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                "inline; filename=\"" + escapeHeaderValue(fileName(relativePath)) + "\"")
                        .body(bytes);
            }
        } catch (IllegalArgumentException ex) {
            log.warn("Preview denied/not found [surfaceUuid={}, path={}]: {}", uuid, path, ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Surface not found");
        } catch (Exception ex) {
            log.warn("Preview failed [surfaceUuid={}, path={}]: {}", uuid, path, ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Preview file not found");
        }
    }

    // ── REST: surfaces ────────────────────────────────────────────────────────

    @GetMapping("/api/surfaces")
    @ResponseBody
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public List<Surface> listSurfaces() {
        log.debug("ENTER listSurfaces");
        return surfaceService.list();
    }

    @GetMapping("/api/surfaces/{uuid}")
    @ResponseBody
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> getSurface(@PathVariable String uuid) {
        log.debug("ENTER getSurface: [uuid={}]", uuid);
        Surface surface = surfaceService.get(uuid);
        if (surface == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(surface);
    }

    @PostMapping("/api/surfaces")
    @ResponseBody
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> createSurface(@RequestBody CreateSurfaceRequest req,
                                           Principal principal) {
        log.debug("ENTER createSurface: [name={}, user={}]",
                req == null ? null : req.name(),
                principal == null ? null : principal.getName());
        if (req == null || req.name() == null || req.name().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required."));
        }
        Surface created = surfaceService.create(req.name(), req.description(), principal.getName());
        return ResponseEntity.ok(created);
    }

    @PutMapping("/api/surfaces/{uuid}")
    @ResponseBody
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> updateSurface(@PathVariable String uuid,
                                           @RequestBody UpdateSurfaceRequest req) {
        log.debug("ENTER updateSurface: [uuid={}]", uuid);
        Surface updated = surfaceService.update(
                uuid,
                req == null ? null : req.name(),
                req == null ? null : req.description(),
                req == null ? null : req.skillUuids(),
                req == null ? null : req.reflectionBindingUuids(),
                req == null ? null : req.jobUuids());
        if (updated == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/api/surfaces/{uuid}")
    @ResponseBody
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> deleteSurface(@PathVariable String uuid) {
        log.debug("ENTER deleteSurface: [uuid={}]", uuid);
        if (!surfaceService.delete(uuid)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    // ── REST: surface session ─────────────────────────────────────────────────

    @GetMapping("/api/surfaces/{uuid}/session")
    @ResponseBody
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> getSurfaceSession(@PathVariable String uuid,
                                               Principal principal) {
        log.debug("ENTER getSurfaceSession: [uuid={}, user={}]", uuid,
                principal == null ? null : principal.getName());
        try {
            AiSession session = surfaceService.ensureSession(uuid, principal.getName());
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "sessionUuid", session.uuid(),
                    "activeAgentTemplateId", session.activeAgentTemplateId() == null
                            ? "" : session.activeAgentTemplateId(),
                    "name", session.name(),
                    "messages", session.messages()));
        } catch (IllegalArgumentException ex) {
            log.warn("getSurfaceSession failed [uuid={}]: {}", uuid, ex.getMessage());
            return ResponseEntity.status(404).body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    // ── Request DTOs ──────────────────────────────────────────────────────────

    public record CreateSurfaceRequest(String name, String description) {
    }

    public record UpdateSurfaceRequest(String name,
                                       String description,
                                       List<String> skillUuids,
                                       List<String> reflectionBindingUuids,
                                       List<String> jobUuids) {
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
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log")) return "text/plain";
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    private static String escapeHeaderValue(String value) {
        return value == null ? "" : value.replace("\"", "'").replace("\n", "").replace("\r", "");
    }
}
