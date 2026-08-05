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
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.reflection.ReflectionService;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.surface.Surface;
import sh.vork.surface.service.SurfaceReflectionContractService;
import sh.vork.surface.service.SurfaceService;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
    private final SurfaceReflectionContractService surfaceReflectionContractService;
    private final ReflectionService reflectionService;
    private final ObjectMapper objectMapper;

    public SurfaceController(SurfaceService surfaceService,
                             SessionFileSystem sessionFileSystem,
                             SurfaceReflectionContractService surfaceReflectionContractService,
                             ReflectionService reflectionService,
                             ObjectMapper objectMapper) {
        this.surfaceService = surfaceService;
        this.sessionFileSystem = sessionFileSystem;
        this.surfaceReflectionContractService = surfaceReflectionContractService;
        this.reflectionService = reflectionService;
        this.objectMapper = objectMapper;
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
                if (mime.startsWith("text/html")) {
                    String html = new String(bytes, StandardCharsets.UTF_8);
                    html = injectPreviewRuntime(html);
                    bytes = html.getBytes(StandardCharsets.UTF_8);
                }
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
        Surface surface = surfaceService.resolveByUuidOrToolId(uuid);
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

        @GetMapping("/api/surfaces/{uuid}/reflection-contracts")
        @ResponseBody
        @PreAuthorize("hasAuthority('USERS_MANAGE')")
        public ResponseEntity<?> getSurfaceReflectionContracts(@PathVariable String uuid,
                                                                 @org.springframework.web.bind.annotation.RequestParam(name = "bindingGroupToolId", required = false) String bindingGroupToolId,
                                                                 @org.springframework.web.bind.annotation.RequestParam(name = "bindingProfileName", required = false) String bindingProfileName,
                                                                                                                     Principal principal) {
            log.debug("ENTER getSurfaceReflectionContracts: [surfaceUuid={}, bindingGroupToolId={}, bindingProfileName={}, user={}]",
                    uuid, bindingGroupToolId, bindingProfileName, principal == null ? null : principal.getName());
                if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", "error", "message", "Access denied"));
                }
                try {
                        surfaceService.ensureSession(uuid, principal.getName());
                var payload = surfaceReflectionContractService.contractsForSurface(uuid, bindingGroupToolId, bindingProfileName);
                        return ResponseEntity.ok(payload);
                } catch (IllegalArgumentException ex) {
                        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(Map.of("status", "error", "message", ex.getMessage()));
                }
        }

        @PostMapping("/api/surfaces/{uuid}/reflections/invoke")
        @ResponseBody
        @PreAuthorize("hasAuthority('USERS_MANAGE')")
        public ResponseEntity<?> invokeSurfaceReflection(@PathVariable String uuid,
                                                                                                         @RequestBody SurfaceReflectionInvokeRequest req,
                                                                                                         Principal principal) {
                log.debug("ENTER invokeSurfaceReflection: [surfaceUuid={}, reflectionId={}, bindingGroupToolId={}, bindingProfileName={}, user={}]",
                                uuid,
                                req == null ? null : req.reflectionId(),
                                req == null ? null : req.bindingGroupToolId(),
                                req == null ? null : req.bindingProfileName(),
                                principal == null ? null : principal.getName());

                if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
                        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", "error", "message", "Access denied"));
                }
                if (req == null || req.reflectionId() == null || req.reflectionId().isBlank()) {
                        return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "reflectionId is required."));
                }

                try {
                        surfaceService.ensureSession(uuid, principal.getName());
                        Surface surface = surfaceService.resolveByUuidOrToolId(uuid);
                        if (surface == null) {
                                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                                                .body(Map.of("status", "error", "message", "Surface not found."));
                        }

                        ReflectionGroup group = reflectionService.getGroupByToolId(req.bindingGroupToolId());
                        if (group == null) {
                            List<String> allowedGroupToolIds = new java.util.ArrayList<>();
                            if (surface.reflectionBindingUuids() != null) {
                                for (String bindingUuid : surface.reflectionBindingUuids()) {
                                    ReflectionBinding attachedBinding = reflectionService.getBindingByUuid(bindingUuid);
                                    if (attachedBinding == null) {
                                        continue;
                                    }
                                    ReflectionGroup attachedGroup = reflectionService.getGroup(attachedBinding.groupUuid());
                                    if (attachedGroup == null || attachedGroup.toolId() == null || attachedGroup.toolId().isBlank()) {
                                        continue;
                                    }
                                    if (!allowedGroupToolIds.contains(attachedGroup.toolId())) {
                                        allowedGroupToolIds.add(attachedGroup.toolId());
                                    }
                                }
                            }
                                return ResponseEntity.badRequest().body(Map.of(
                                                "status", "error",
                                                "message", "Unknown binding group toolId. Use getSurfaceReflectionContracts bindings[].bindingId (not surfaceId).",
                                    "providedBindingGroupToolId", req.bindingGroupToolId(),
                                    "allowedBindingGroupToolIds", allowedGroupToolIds));
                        }

                        String profileName = req.bindingProfileName() == null || req.bindingProfileName().isBlank()
                            ? "default"
                            : req.bindingProfileName().trim();
                        ReflectionBinding binding = reflectionService.getBinding(group.uuid(), profileName);
                        if (binding == null) {
                                return ResponseEntity.badRequest().body(Map.of(
                                                "status", "error",
                                    "message", "Reflection binding profile not found for group toolId/profileName."));
                        }
                        if (surface.reflectionBindingUuids() == null || !surface.reflectionBindingUuids().contains(binding.uuid())) {
                            return ResponseEntity.badRequest().body(Map.of(
                                    "status", "error",
                                    "message", "Binding profile is not attached to this surface."));
                        }

                        Map<String, Object> args = req.args() == null ? Map.of() : req.args();
                        String raw = reflectionService.executeRestReflection(
                                        req.reflectionId().trim(),
                                        args,
                                        binding.name(),
                                        principal.getName());

                        Object payload;
                        try {
                                payload = objectMapper.readValue(raw, Object.class);
                        } catch (Exception ignored) {
                            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body(Map.of(
                                            "status", "error",
                                            "message", raw));
                        }

                        if (!(payload instanceof Map<?, ?> wrapper)) {
                            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body(Map.of(
                                            "status", "error",
                                            "message", "Unexpected reflection response envelope."));
                        }

                        Object statusValue = wrapper.containsKey("status") ? wrapper.get("status") : "error";
                        String status = String.valueOf(statusValue);
                        if (!"ok".equalsIgnoreCase(status)) {
                            Object message = wrapper.get("message");
                            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body(Map.of(
                                            "status", "error",
                                            "message", message == null ? "Reflection execution failed." : String.valueOf(message)));
                        }

                        int httpStatus = 200;
                        Object statusCode = wrapper.get("statusCode");
                        if (statusCode instanceof Number number) {
                            httpStatus = number.intValue();
                        }

                        Reflection reflection = reflectionService.getReflectionById(req.reflectionId().trim());
                        String responseContentType = reflection == null
                                ? "application/json"
                                : reflection.responseContentType();
                        MediaType mediaType;
                        try {
                            mediaType = MediaType.parseMediaType(responseContentType);
                        } catch (Exception ex) {
                            mediaType = MediaType.APPLICATION_JSON;
                        }

                        Object body = wrapper.get("body");
                        String bodyText;
                        if (body == null) {
                            bodyText = "";
                        } else if (body instanceof String bodyString) {
                            bodyText = bodyString;
                        } else {
                            try {
                                bodyText = objectMapper.writeValueAsString(body);
                            } catch (Exception ex) {
                                bodyText = String.valueOf(body);
                            }
                        }

                        return ResponseEntity.status(httpStatus)
                                .contentType(mediaType)
                                .body(bodyText);
                } catch (IllegalArgumentException ex) {
                        return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
                }
        }

        @GetMapping("/surface/runtime/v1/reflections.js")
        @ResponseBody
        @PreAuthorize("hasAuthority('USERS_MANAGE')")
        public ResponseEntity<String> surfaceReflectionRuntimeHelper() {
                String script = """
(function () {
    'use strict';

    function detectSurfaceUuid() {
        if (window.__VORK_SURFACE_UUID__ && typeof window.__VORK_SURFACE_UUID__ === 'string') {
            return window.__VORK_SURFACE_UUID__;
        }
        var match = window.location.pathname.match(/^\\/surface\\/([^\\/]+)\\/preview(?:\\/|$)/);
        return match ? decodeURIComponent(match[1]) : null;
    }

    async function invoke(options) {
        options = options || {};
        var surfaceUuid = options.surfaceUuid || detectSurfaceUuid();
        if (!surfaceUuid) {
            throw new Error('Cannot resolve surfaceUuid for reflection invocation.');
        }
        if (!options.reflectionId) {
            throw new Error('reflectionId is required.');
        }
        if (!options.bindingGroupToolId) {
            throw new Error('bindingGroupToolId is required.');
        }
        if (!options.bindingProfileName) {
            throw new Error('bindingProfileName is required.');
        }

        var callId = (window.crypto && window.crypto.randomUUID)
            ? window.crypto.randomUUID()
            : ('call-' + Date.now() + '-' + Math.floor(Math.random() * 100000));

        if (window.vorkPreviewConsole && typeof window.vorkPreviewConsole.log === 'function') {
            window.vorkPreviewConsole.log('reflection:start', {
                callId: callId,
                reflectionId: options.reflectionId,
                reflectionName: options.reflectionName || options.reflectionId,
                bindingGroupToolId: options.bindingGroupToolId,
                bindingProfileName: options.bindingProfileName,
                request: {
                    args: options.args || {}
                }
            });
        }

        var startedAt = Date.now();

        var response = await fetch('/api/surfaces/' + encodeURIComponent(surfaceUuid) + '/reflections/invoke', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                reflectionId: options.reflectionId,
                args: options.args || {},
                bindingGroupToolId: options.bindingGroupToolId,
                bindingProfileName: options.bindingProfileName
            })
        });

        var contentType = response.headers.get('content-type') || '';
        var isJson = contentType.indexOf('application/json') >= 0;
        var payload = isJson ? await response.json() : await response.text();
        if (!response.ok) {
            var message;
            if (isJson && payload && typeof payload === 'object') {
                message = payload.message || payload.error || JSON.stringify(payload);
            } else {
                message = payload || ('Reflection invoke failed with HTTP ' + response.status);
            }

            if (window.vorkPreviewConsole && typeof window.vorkPreviewConsole.log === 'function') {
                window.vorkPreviewConsole.log('reflection:error', {
                    callId: callId,
                    reflectionId: options.reflectionId,
                    reflectionName: options.reflectionName || options.reflectionId,
                    bindingGroupToolId: options.bindingGroupToolId,
                    bindingProfileName: options.bindingProfileName,
                    durationMs: Date.now() - startedAt,
                    error: message,
                    response: {
                        statusCode: response.status,
                        contentType: contentType,
                        body: payload
                    }
                });
            }
            throw new Error(message);
        }

        if (window.vorkPreviewConsole && typeof window.vorkPreviewConsole.log === 'function') {
            window.vorkPreviewConsole.log('reflection:success', {
                callId: callId,
                reflectionId: options.reflectionId,
                reflectionName: options.reflectionName || options.reflectionId,
                bindingGroupToolId: options.bindingGroupToolId,
                bindingProfileName: options.bindingProfileName,
                durationMs: Date.now() - startedAt,
                response: {
                    statusCode: response.status,
                    contentType: contentType,
                    body: payload
                }
            });
        }
        return payload;
    }

    async function getContracts(options) {
        options = options || {};
        var surfaceUuid = options.surfaceUuid || detectSurfaceUuid();
        if (!surfaceUuid) {
            throw new Error('Cannot resolve surfaceUuid for contract lookup.');
        }

        var queryParams = [];
        if (options.bindingGroupToolId) {
            queryParams.push('bindingGroupToolId=' + encodeURIComponent(options.bindingGroupToolId));
        }
        if (options.bindingProfileName) {
            queryParams.push('bindingProfileName=' + encodeURIComponent(options.bindingProfileName));
        }
        var query = queryParams.length > 0 ? ('?' + queryParams.join('&')) : '';
        var response = await fetch('/api/surfaces/' + encodeURIComponent(surfaceUuid) + '/reflection-contracts' + query);
        var payload = await response.json();
        if (!response.ok) {
            var message = (payload && (payload.message || payload.error)) || ('Contract lookup failed with HTTP ' + response.status);
            throw new Error(message);
        }
        return payload;
    }

    window.vork = window.vork || {};
    window.vork.reflections = {
        invoke: invoke,
        getContracts: getContracts
    };
})();
""";
                return ResponseEntity.ok()
                                .contentType(MediaType.parseMediaType("application/javascript"))
                                .body(script);
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

    public record SurfaceReflectionInvokeRequest(String reflectionId,
                                                 String bindingGroupToolId,
                                                 String bindingProfileName,
                                                 Map<String, Object> args) {
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

    private static String injectPreviewRuntime(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }
        if (html.contains("/js/surface-preview-console.js")) {
            return html;
        }

        String injection = """
<script src="/surface/runtime/v1/reflections.js"></script>
<script src="/js/surface-preview-console.js"></script>
""";

        int bodyClose = html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
        if (bodyClose >= 0) {
            return html.substring(0, bodyClose) + injection + html.substring(bodyClose);
        }
        return html + injection;
    }
}
