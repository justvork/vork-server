package sh.vork.surface.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import sh.vork.ai.entity.AiSession;
import sh.vork.ai.service.ChatService;
import sh.vork.orm.DatabaseRepository;
import sh.vork.reflection.Reflection;
import sh.vork.reflection.ReflectionBinding;
import sh.vork.reflection.ReflectionGroup;
import sh.vork.reflection.ReflectionService;
import sh.vork.filesystem.FileArea;
import sh.vork.filesystem.SessionFileSystem;
import sh.vork.skill.Skill;
import sh.vork.surface.ArtifactStatus;
import sh.vork.surface.Surface;
import sh.vork.surface.service.SurfaceReflectionContractService;
import sh.vork.surface.service.SurfaceService;
import sh.vork.surface.service.SurfaceSkillExecutionService;
import sh.vork.util.ZipArchiveUtil;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Page and REST API controller for the Surfaces management UI and Surface Editor.
 */
@Controller
public class SurfaceController {

    private static final Logger log = LoggerFactory.getLogger(SurfaceController.class);
    private static final String SURFACE_EXPORT_ENTRY = "surface.json";
    private static final String LEGACY_SURFACE_EXPORT_ENTRY = "definition.json";
    private static final String EDITOR_FILES_ZIP_DIR = "assets";
    private static final String EDITOR_FILES_PREFIX = "assets/";
    private static final String LEGACY_EDITOR_FILES_PREFIX = "sessions/editor/";
    private static final String SNAPSHOT_VERSION = "SNAPSHOT";
    private static final int GROUP_ID_MIN_LEN = 3;
    private static final int GROUP_ID_MAX_LEN = 64;
    private static final int ARTIFACT_ID_MIN_LEN = 3;
    private static final int ARTIFACT_ID_MAX_LEN = 64;
    private static final int VERSION_MAX_LEN = 16;
    private static final Pattern GROUP_ID_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");
    private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9]+$");

    private final SurfaceService surfaceService;
    private final DatabaseRepository<Surface> surfaceRepository;
    private final SessionFileSystem sessionFileSystem;
    private final SurfaceReflectionContractService surfaceReflectionContractService;
    private final ReflectionService reflectionService;
    private final ChatService chatService;
    private final SurfaceSkillExecutionService surfaceSkillExecutionService;
    private final ObjectMapper objectMapper;

    public SurfaceController(SurfaceService surfaceService,
                             DatabaseRepository<Surface> surfaceRepository,
                             SessionFileSystem sessionFileSystem,
                             SurfaceReflectionContractService surfaceReflectionContractService,
                             ReflectionService reflectionService,
                             ChatService chatService,
                             SurfaceSkillExecutionService surfaceSkillExecutionService,
                             ObjectMapper objectMapper) {
        this.surfaceService = surfaceService;
        this.surfaceRepository = surfaceRepository;
        this.sessionFileSystem = sessionFileSystem;
        this.surfaceReflectionContractService = surfaceReflectionContractService;
        this.reflectionService = reflectionService;
        this.chatService = chatService;
        this.surfaceSkillExecutionService = surfaceSkillExecutionService;
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
        String groupId = req.groupId() == null ? "" : req.groupId().trim();
        String artifactId = req.artifactId() == null ? "" : req.artifactId().trim();
        String identityError = validateArtifactIdentity(groupId, artifactId, SNAPSHOT_VERSION);
        if (identityError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", identityError));
        }
        try {
            Surface created = surfaceService.create(req.name(), req.description(), principal.getName(), groupId, artifactId);
            if (req.published() != null
                    || req.logoDataUrl() != null
                    || req.assignedUserUuids() != null
                    || req.accessPolicy() != null) {
                created = surfaceService.update(
                        created.uuid(),
                        created.name(),
                        created.description(),
                        created.skillUuids(),
                        created.reflectionBindingUuids(),
                        created.jobUuids(),
                        req.published(),
                        req.logoDataUrl(),
                        req.assignedUserUuids(),
                        req.toAccessPolicy());
            }
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/api/surfaces/{uuid}")
    @ResponseBody
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> updateSurface(@PathVariable String uuid,
                                           @RequestBody UpdateSurfaceRequest req) {
        log.debug("ENTER updateSurface: [uuid={}]", uuid);
        Surface existing = surfaceService.get(uuid);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            Surface updated;
            if (existing.isSnapshotMutable()) {
                updated = surfaceService.update(
                        existing.uuid(),
                        req == null ? null : req.name(),
                        req == null ? null : req.description(),
                        req == null ? null : req.skillUuids(),
                        req == null ? null : req.reflectionBindingUuids(),
                        req == null ? null : req.jobUuids(),
                        req == null ? null : req.published(),
                        req == null ? null : req.logoDataUrl(),
                        req == null ? null : req.assignedUserUuids(),
                        req == null ? null : req.toAccessPolicy());
            } else {
                if (req == null) {
                    return ResponseEntity.badRequest().body(Map.of("error", "Request body is required."));
                }
                if (hasImmutableSurfaceFieldChanges(existing, req)) {
                    return ResponseEntity.status(403).body(Map.of(
                            "error",
                            "Immutable surface versions only allow updates to published, assigned users, and access policy routes."));
                }
                updated = surfaceService.updatePublicationSettings(
                        existing.uuid(),
                        req.published(),
                        req.assignedUserUuids(),
                        req.toAccessPolicy());
            }
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/api/surfaces/my-apps")
    @ResponseBody
    public ResponseEntity<?> mySurfaceApps(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Access denied"));
        }
        return ResponseEntity.ok(surfaceService.listPublishedHomeAppsForUser(principal.getName()));
    }

    @GetMapping("/apps/published/{uuid}")
    @ResponseBody
    public ResponseEntity<?> publishedSurfaceIndex(@PathVariable("uuid") String uuid,
                                                   Principal principal) {
        return publishedSurfaceFile(uuid, "index.html", principal);
    }

    @GetMapping("/apps/published/{uuid}/{*assetPath}")
    @ResponseBody
    public ResponseEntity<?> publishedSurfaceFile(@PathVariable("uuid") String uuid,
                                                  @PathVariable("assetPath") String assetPath,
                                                  Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        Surface surface = surfaceService.get(uuid);
        if (surface == null || !surface.published()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Surface not found");
        }
        boolean assigned = surfaceService.isUserAssigned(surface, principal.getName());
        if (!assigned && !hasManageUsersPermission()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        return servePublishedSurfaceFile(surface, assetPath, principal.getName());
    }

    @GetMapping("/apps/published/{assetPath:.+}")
    @ResponseBody
    public ResponseEntity<?> publishedSurfaceRootAssetFallback(@PathVariable("assetPath") String assetPath,
                                                               Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        List<Surface> candidates = surfaceService.listPublishedHomeAppsForUser(principal.getName()).stream()
                .map(link -> surfaceService.get(link.uuid()))
                .filter(surface -> surface != null)
                .toList();
        for (Surface surface : candidates) {
            ResponseEntity<?> response = servePublishedSurfaceFile(surface, assetPath, principal.getName());
            if (response.getStatusCode() == HttpStatus.OK) {
                return response;
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Surface file not found");
    }

    @GetMapping("/apps/private/{path}")
    @ResponseBody
    public ResponseEntity<?> privateSurfaceIndex(@PathVariable("path") String path,
                                                 Principal principal) {
        return privateSurfaceFile(path, "index.html", principal);
    }

    @GetMapping("/apps/private/{path}/{*assetPath}")
    @ResponseBody
    public ResponseEntity<?> privateSurfaceFile(@PathVariable("path") String path,
                                                @PathVariable("assetPath") String assetPath,
                                                Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        Surface surface = surfaceService.findPublishedByPrivatePath(path);
        if (surface == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Surface not found");
        }
        boolean assigned = surfaceService.isUserAssigned(surface, principal.getName());
        if (!assigned && !hasManageUsersPermission()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Access denied");
        }
        return servePublishedSurfaceFile(surface, assetPath, principal.getName());
    }

    @GetMapping("/apps/public/{path}")
    @ResponseBody
    public ResponseEntity<?> publicSurfaceIndex(@PathVariable("path") String path) {
        return publicSurfaceFile(path, "index.html");
    }

    @GetMapping("/apps/public/{path}/{*assetPath}")
    @ResponseBody
    public ResponseEntity<?> publicSurfaceFile(@PathVariable("path") String path,
                                               @PathVariable("assetPath") String assetPath) {
        Surface surface = surfaceService.findPublishedByPublicPath(path);
        if (surface == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Surface not found");
        }
        return servePublishedSurfaceFile(surface, assetPath, null);
    }

    @DeleteMapping("/api/surfaces/{uuid}")
    @ResponseBody
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> deleteSurface(@PathVariable String uuid) {
        log.debug("ENTER deleteSurface: [uuid={}]", uuid);
        Surface existing = surfaceService.get(uuid);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }
        if (!existing.isDeletable()) {
            log.warn("Refused to delete immutable surface [uuid={}, status={}]", uuid, existing.artifactStatus());
            return ResponseEntity.status(403).body(Map.of("error", "Only SNAPSHOT, SUBMITTED, or REJECTED surfaces can be deleted."));
        }
        if (!surfaceService.delete(existing.uuid())) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/api/surfaces/{uuid}/export")
    @ResponseBody
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> exportSurface(@PathVariable String uuid) {
        return exportSurfaceById(uuid);
    }

    private ResponseEntity<?> exportSurfaceById(String uuid) {
        Surface surface = surfaceService.get(uuid);
        if (surface == null) {
            return ResponseEntity.notFound().build();
        }

        byte[] archive;
        try {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            Map<String, Object> artifact = new LinkedHashMap<>();
            artifact.put("uuid", surface.uuid());
            artifact.put("name", surface.name());
            artifact.put("description", surface.description());
            artifact.put("skillUuids", surface.skillUuids());
            artifact.put("reflectionBindingUuids", surface.reflectionBindingUuids());
            artifact.put("jobUuids", surface.jobUuids());
            artifact.put("logoDataUrl", surface.logoDataUrl());
            artifact.put("accessPolicy", surface.accessPolicy());
            artifact.put("groupId", surface.groupId());
            artifact.put("artifactId", surface.artifactId());
            artifact.put("version", surface.version());
            artifact.put("artifactStatus", surface.artifactStatus());

            Map<String, Object> pkg = new LinkedHashMap<>();
            pkg.put("vorkSurfaceExport", "1.0");
            pkg.put("surface", artifact);

            entries.put(SURFACE_EXPORT_ENTRY, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(pkg));
            if (surface.sessionUuid() != null && !surface.sessionUuid().isBlank()) {
                collectSessionEntries(entries, surface.sessionUuid(), EDITOR_FILES_ZIP_DIR);
            }
            archive = ZipArchiveUtil.write(entries);
        } catch (Exception ex) {
            log.warn("Surface export failed [uuid={}]: {}", uuid, ex.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", "Failed to build surface export archive."));
        }

        String safeName = surface.name() == null
                ? "surface"
                : surface.name().replaceAll("[^a-zA-Z0-9._-]", "_");
        String filename = "surface-" + safeName + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(archive);
    }

    @PostMapping("/api/surfaces/import")
    @ResponseBody
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> importSurface(@RequestParam("file") MultipartFile file,
                                           Principal principal) {
        SurfaceExportPackage pkg;
        Map<String, byte[]> zipEntries;
        try {
            if (file == null || file.isEmpty()) {
                return ResponseEntity.badRequest().body(new SurfaceImportResult("error", null, "Import file is required."));
            }
            zipEntries = ZipArchiveUtil.read(file.getInputStream());
            byte[] definition = zipEntries.get(SURFACE_EXPORT_ENTRY);
            if (definition == null) {
                definition = zipEntries.get(LEGACY_SURFACE_EXPORT_ENTRY);
            }
            if (definition == null) {
                return ResponseEntity.badRequest().body(new SurfaceImportResult("error", null, "Archive is missing surface.json."));
            }
            pkg = objectMapper.readValue(definition, SurfaceExportPackage.class);
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(new SurfaceImportResult("error", null, "Invalid surface zip import file."));
        }

        if (pkg == null || pkg.vorkSurfaceExport() == null || pkg.vorkSurfaceExport().isBlank() || pkg.surface() == null) {
            return ResponseEntity.badRequest().body(new SurfaceImportResult("error", null, "Invalid surface export package."));
        }

        SurfaceArtifact incoming = pkg.surface();
        if (incoming.name() == null || incoming.name().isBlank()) {
            return ResponseEntity.badRequest().body(new SurfaceImportResult("error", null, "Surface name is required."));
        }

        String username = principal == null || principal.getName() == null || principal.getName().isBlank()
                ? "anonymous"
                : principal.getName();

        String groupId = incoming.groupId() == null ? "" : incoming.groupId().trim();
        String artifactId = incoming.artifactId() == null ? "" : incoming.artifactId().trim();
        String version = incoming.version() == null || incoming.version().isBlank()
                ? SNAPSHOT_VERSION
                : incoming.version().trim();

        String identityError = validateArtifactIdentity(groupId, artifactId, version);
        if (identityError != null) {
            return ResponseEntity.badRequest().body(new SurfaceImportResult("error", null, identityError));
        }
        if (incoming.artifactStatus() != null && incoming.artifactStatus() != ArtifactStatus.SNAPSHOT) {
            return ResponseEntity.badRequest().body(new SurfaceImportResult(
                    "error", null, "Only SNAPSHOT surfaces are importable in this flow."));
        }
        String incomingUuid = toVid(groupId, artifactId, version);
        if (incoming.uuid() != null && !incoming.uuid().isBlank() && !incomingUuid.equals(incoming.uuid().trim())) {
            return ResponseEntity.badRequest().body(new SurfaceImportResult(
                    "error", incomingUuid, "Incoming uuid does not match deterministic VID."));
        }

        Surface existing = surfaceService.get(incomingUuid);
        if (existing != null && !existing.isSnapshotMutable()) {
            return ResponseEntity.badRequest().body(new SurfaceImportResult(
                    "error", incomingUuid, "Only SNAPSHOT surfaces are mutable/importable in this flow."));
        }

        Surface targetSurface = existing;
        if (targetSurface == null) {
            try {
                targetSurface = surfaceService.create(incoming.name(), incoming.description(), username, groupId, artifactId);
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest().body(new SurfaceImportResult("error", incomingUuid, ex.getMessage()));
            }
        }

        Surface updated = surfaceService.update(
                targetSurface.uuid(),
                incoming.name(),
                incoming.description(),
                incoming.skillUuids(),
                incoming.reflectionBindingUuids(),
            incoming.jobUuids(),
            null,
            incoming.logoDataUrl(),
            null,
            incoming.accessPolicy());
        Surface target = updated == null ? surfaceRepository.get(targetSurface.uuid()) : updated;
        if (target == null) {
            return ResponseEntity.internalServerError().body(new SurfaceImportResult("error", null, "Failed to create imported surface."));
        }

        try {
            AiSession editorSession = surfaceService.ensureSession(target.uuid(), username);
            restoreSessionFiles(editorSession.uuid(), zipEntries, EDITOR_FILES_PREFIX);
            // Backward compatibility for old archive layout.
            restoreSessionFiles(editorSession.uuid(), zipEntries, LEGACY_EDITOR_FILES_PREFIX);
        } catch (Exception ex) {
            log.warn("Surface import file restore failed [surfaceUuid={}]: {}", target.uuid(), ex.getMessage());
            return ResponseEntity.badRequest().body(new SurfaceImportResult("error", target.uuid(), "Failed to import surface files."));
        }

        String status = existing == null ? "imported" : "updated";
        return ResponseEntity.ok(new SurfaceImportResult(status, target.uuid(), null));
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
                    surfaceService.ensureExecutionSession(uuid, principal.getName());
                        Surface surface = surfaceService.get(uuid);
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
                                    ReflectionGroup attachedGroup = reflectionService.getBindingGroup(attachedBinding);
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

    @GetMapping("/api/surfaces/{uuid}/skill-contracts")
    @ResponseBody
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> getSurfaceSkillContracts(@PathVariable String uuid,
                                                      Principal principal) {
        log.debug("ENTER getSurfaceSkillContracts: [surfaceUuid={}, user={}]",
                uuid, principal == null ? null : principal.getName());
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "error", "message", "Access denied"));
        }

        List<Map<String, Object>> skills = new java.util.ArrayList<>();
        List<Skill> attachedSkills;
        try {
            attachedSkills = surfaceService.listAttachedSkills(uuid);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }

        for (Skill skill : attachedSkills) {
            SurfaceService.PublicSkillId ids = surfaceService.publicIdsFor(skill);
            Object outputSchema = Map.of();
            try {
                outputSchema = objectMapper.readValue(skill.outputSchema(), Object.class);
            } catch (Exception ignored) {
                // Return empty schema when stored value is malformed.
            }
            skills.add(Map.of(
                    "groupId", ids.groupId(),
                    "skillId", ids.skillId(),
                    "toolName", skill.toolName(),
                    "name", skill.name(),
                    "outputContentType", skill.outputContentType(),
                    "outputSchema", outputSchema));
        }

        return ResponseEntity.ok(Map.of(
            "surfaceUuid", uuid,
                "skills", skills));
    }

    @PostMapping("/api/surfaces/{uuid}/skills/invoke")
    @ResponseBody
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> startSurfaceSkillExecution(@PathVariable String uuid,
                                                        @RequestBody SurfaceSkillInvokeRequest req,
                                                        Principal principal) {
        log.debug("ENTER startSurfaceSkillExecution: [surfaceUuid={}, groupId={}, skillId={}, user={}]",
                uuid,
                req == null ? null : req.groupId(),
                req == null ? null : req.skillId(),
                principal == null ? null : principal.getName());
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "error", "message", "Access denied"));
        }
        if (req == null || req.groupId() == null || req.groupId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "groupId is required."));
        }
        if (req == null || req.skillId() == null || req.skillId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "skillId is required."));
        }

        try {
            Skill skill = surfaceService.resolveAttachedSkillByPublicIds(uuid, req.groupId(), req.skillId());
            if (!"application/json".equalsIgnoreCase(skill.outputContentType())
                    || skill.outputSchema() == null
                    || skill.outputSchema().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "message", "Skill output contract is incomplete. outputContentType=application/json and outputSchema are required."));
            }

            AiSession executionSession = surfaceService.ensureExecutionSession(uuid, principal.getName());
            chatService.addSessionSkill(executionSession.uuid(), skill.uuid());

            sh.vork.ai.AiProvider provider;
            try {
                provider = sh.vork.ai.AiProvider.valueOf(executionSession.provider());
            } catch (Exception ex) {
                provider = sh.vork.ai.AiProvider.GEMINI;
            }

            var started = surfaceSkillExecutionService.start(
                    uuid,
                    executionSession.uuid(),
                    skill,
                    req.args() == null ? Map.of() : req.args(),
                    provider);

            return ResponseEntity.ok(Map.of(
                    "status", "accepted",
                    "executionId", started.executionId(),
                    "state", started.state().name(),
                    "executionSessionUuid", started.executionSessionUuid(),
                    "groupId", req.groupId(),
                    "skillId", req.skillId()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @GetMapping("/api/surfaces/{uuid}/skills/executions/{executionId}")
    @ResponseBody
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> pollSurfaceSkillExecution(@PathVariable String uuid,
                                                       @PathVariable String executionId,
                                                       @RequestParam(name = "waitMs", defaultValue = "15000") long waitMs,
                                                       Principal principal) {
        log.debug("ENTER pollSurfaceSkillExecution: [surfaceUuid={}, executionId={}, waitMs={}, user={}]",
                uuid, executionId, waitMs, principal == null ? null : principal.getName());
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("status", "error", "message", "Access denied"));
        }

        try {
            var snapshot = surfaceSkillExecutionService.poll(uuid, executionId, waitMs);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "executionId", snapshot.executionId(),
                    "state", snapshot.state().name(),
                    "outputContentType", snapshot.outputContentType() == null ? "" : snapshot.outputContentType(),
                    "result", snapshot.result(),
                    "error", snapshot.error() == null ? "" : snapshot.error(),
                    "startedAt", snapshot.startedAt(),
                    "updatedAt", snapshot.updatedAt(),
                    "completedAt", snapshot.completedAt() == null ? 0L : snapshot.completedAt()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @GetMapping("/api/apps/published/{uuid}/reflection-contracts")
    @ResponseBody
    public ResponseEntity<?> getPublishedSurfaceReflectionContracts(@PathVariable String uuid,
                                                                    @RequestParam(name = "bindingGroupToolId", required = false) String bindingGroupToolId,
                                                                    @RequestParam(name = "bindingProfileName", required = false) String bindingProfileName,
                                                                    Principal principal) {
        ResponseEntity<?> access = ensurePublishedSurfaceAccess(uuid, principal);
        if (access != null) {
            return access;
        }
        return getSurfaceReflectionContracts(uuid, bindingGroupToolId, bindingProfileName, principal);
    }

    @PostMapping("/api/apps/published/{uuid}/reflections/invoke")
    @ResponseBody
    public ResponseEntity<?> invokePublishedSurfaceReflection(@PathVariable String uuid,
                                                              @RequestBody SurfaceReflectionInvokeRequest req,
                                                              Principal principal) {
        ResponseEntity<?> access = ensurePublishedSurfaceAccess(uuid, principal);
        if (access != null) {
            return access;
        }
        try {
            // Prime execution context for published users before invoking reflection.
            surfaceService.ensureExecutionSession(uuid, principal.getName());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", "error", "message", ex.getMessage()));
        }
        return invokeSurfaceReflection(uuid, req, principal);
    }

    @GetMapping("/api/apps/published/{uuid}/skill-contracts")
    @ResponseBody
    public ResponseEntity<?> getPublishedSurfaceSkillContracts(@PathVariable String uuid,
                                                               Principal principal) {
        ResponseEntity<?> access = ensurePublishedSurfaceAccess(uuid, principal);
        if (access != null) {
            return access;
        }
        return getSurfaceSkillContracts(uuid, principal);
    }

    @PostMapping("/api/apps/published/{uuid}/skills/invoke")
    @ResponseBody
    public ResponseEntity<?> startPublishedSurfaceSkillExecution(@PathVariable String uuid,
                                                                 @RequestBody SurfaceSkillInvokeRequest req,
                                                                 Principal principal) {
        ResponseEntity<?> access = ensurePublishedSurfaceAccess(uuid, principal);
        if (access != null) {
            return access;
        }
        return startSurfaceSkillExecution(uuid, req, principal);
    }

    @GetMapping("/api/apps/published/{uuid}/skills/executions/{executionId}")
    @ResponseBody
    public ResponseEntity<?> pollPublishedSurfaceSkillExecution(@PathVariable String uuid,
                                                                @PathVariable String executionId,
                                                                @RequestParam(name = "waitMs", defaultValue = "15000") long waitMs,
                                                                Principal principal) {
        ResponseEntity<?> access = ensurePublishedSurfaceAccess(uuid, principal);
        if (access != null) {
            return access;
        }
        return pollSurfaceSkillExecution(uuid, executionId, waitMs, principal);
    }

    @GetMapping("/apps/runtime/v1/reflections.js")
    @ResponseBody
    public ResponseEntity<String> publishedReflectionRuntimeHelper() {
        String script = """
(function () {
    'use strict';

    function detectSurfaceUuid() {
        if (window.__VORK_SURFACE_UUID__ && typeof window.__VORK_SURFACE_UUID__ === 'string') {
            return window.__VORK_SURFACE_UUID__;
        }
        var match = window.location.pathname.match(/^\\/apps\\/published\\/([^\\/]+)(?:\\/|$)/);
        return match ? decodeURIComponent(match[1]) : null;
    }

    async function invoke(options) {
        options = options || {};
        var surfaceUuid = options.surfaceUuid || detectSurfaceUuid();
        if (!surfaceUuid) throw new Error('Cannot resolve surfaceUuid for reflection invocation.');
        if (!options.reflectionId) throw new Error('reflectionId is required.');
        if (!options.bindingGroupToolId) throw new Error('bindingGroupToolId is required.');
        if (!options.bindingProfileName) throw new Error('bindingProfileName is required.');

        var response = await fetch('/api/apps/published/' + encodeURIComponent(surfaceUuid) + '/reflections/invoke', {
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
            var message = isJson && payload && typeof payload === 'object'
                ? (payload.message || payload.error || JSON.stringify(payload))
                : (payload || ('Reflection invoke failed with HTTP ' + response.status));
            throw new Error(message);
        }
        return payload;
    }

    async function getContracts(options) {
        options = options || {};
        var surfaceUuid = options.surfaceUuid || detectSurfaceUuid();
        if (!surfaceUuid) throw new Error('Cannot resolve surfaceUuid for contract lookup.');

        var queryParams = [];
        if (options.bindingGroupToolId) {
            queryParams.push('bindingGroupToolId=' + encodeURIComponent(options.bindingGroupToolId));
        }
        if (options.bindingProfileName) {
            queryParams.push('bindingProfileName=' + encodeURIComponent(options.bindingProfileName));
        }
        var query = queryParams.length > 0 ? ('?' + queryParams.join('&')) : '';
        var response = await fetch('/api/apps/published/' + encodeURIComponent(surfaceUuid) + '/reflection-contracts' + query);
        var payload = await response.json();
        if (!response.ok) {
            throw new Error((payload && (payload.message || payload.error)) || ('Contract lookup failed with HTTP ' + response.status));
        }
        return payload;
    }

    window.vork = window.vork || {};
    window.vork.reflections = { invoke: invoke, getContracts: getContracts };
})();
""";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript"))
                .body(script);
    }

    @GetMapping("/apps/runtime/v1/skills.js")
    @ResponseBody
    public ResponseEntity<String> publishedSkillRuntimeHelper() {
        String script = """
(function () {
    'use strict';

    function detectSurfaceUuid() {
        if (window.__VORK_SURFACE_UUID__ && typeof window.__VORK_SURFACE_UUID__ === 'string') {
            return window.__VORK_SURFACE_UUID__;
        }
        var match = window.location.pathname.match(/^\\/apps\\/published\\/([^\\/]+)(?:\\/|$)/);
        return match ? decodeURIComponent(match[1]) : null;
    }

    async function invoke(options) {
        options = options || {};
        var surfaceUuid = options.surfaceUuid || detectSurfaceUuid();
        if (!surfaceUuid) throw new Error('Cannot resolve surfaceUuid for skill invocation.');
        if (!options.groupId) throw new Error('groupId is required.');
        if (!options.skillId) throw new Error('skillId is required.');

        var startRes = await fetch('/api/apps/published/' + encodeURIComponent(surfaceUuid) + '/skills/invoke', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                groupId: options.groupId,
                skillId: options.skillId,
                args: options.args || {}
            })
        });
        var startPayload = await startRes.json();
        if (!startRes.ok) {
            throw new Error(startPayload.message || startPayload.error || ('Skill invoke failed with HTTP ' + startRes.status));
        }

        var executionId = startPayload.executionId;
        if (!executionId) throw new Error('Skill execution did not return an executionId.');

        var waitMs = typeof options.waitMs === 'number' ? options.waitMs : 15000;
        while (true) {
            var pollRes = await fetch('/api/apps/published/' + encodeURIComponent(surfaceUuid)
                + '/skills/executions/' + encodeURIComponent(executionId)
                + '?waitMs=' + encodeURIComponent(waitMs));
            var pollPayload = await pollRes.json();
            if (!pollRes.ok) {
                throw new Error(pollPayload.message || pollPayload.error || ('Skill poll failed with HTTP ' + pollRes.status));
            }

            var state = String(pollPayload.state || '').toUpperCase();
            if (state === 'COMPLETED') {
                return {
                    executionId: executionId,
                    outputContentType: pollPayload.outputContentType || 'application/json',
                    result: pollPayload.result
                };
            }
            if (state === 'FAILED') {
                throw new Error(pollPayload.error || 'Skill execution failed.');
            }
        }
    }

    async function getContracts(options) {
        options = options || {};
        var surfaceUuid = options.surfaceUuid || detectSurfaceUuid();
        if (!surfaceUuid) throw new Error('Cannot resolve surfaceUuid for skill contract lookup.');
        var response = await fetch('/api/apps/published/' + encodeURIComponent(surfaceUuid) + '/skill-contracts');
        var payload = await response.json();
        if (!response.ok) {
            throw new Error(payload.message || payload.error || ('Skill contract lookup failed with HTTP ' + response.status));
        }
        return payload;
    }

    window.vork = window.vork || {};
    window.vork.skills = { invoke: invoke, getContracts: getContracts };
})();
""";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/javascript"))
                .body(script);
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

        @GetMapping("/surface/runtime/v1/skills.js")
        @ResponseBody
        @PreAuthorize("hasAuthority('USERS_MANAGE')")
        public ResponseEntity<String> surfaceSkillRuntimeHelper() {
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
        if (!surfaceUuid) throw new Error('Cannot resolve surfaceUuid for skill invocation.');
        if (!options.groupId) throw new Error('groupId is required.');
        if (!options.skillId) throw new Error('skillId is required.');

        var startRes = await fetch('/api/surfaces/' + encodeURIComponent(surfaceUuid) + '/skills/invoke', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                groupId: options.groupId,
                skillId: options.skillId,
                args: options.args || {}
            })
        });
        var startPayload = await startRes.json();
        if (!startRes.ok) {
            throw new Error(startPayload.message || startPayload.error || ('Skill invoke failed with HTTP ' + startRes.status));
        }

        var executionId = startPayload.executionId;
        if (!executionId) throw new Error('Skill execution did not return an executionId.');

        var waitMs = typeof options.waitMs === 'number' ? options.waitMs : 15000;
        while (true) {
            var pollRes = await fetch('/api/surfaces/' + encodeURIComponent(surfaceUuid)
                + '/skills/executions/' + encodeURIComponent(executionId)
                + '?waitMs=' + encodeURIComponent(waitMs));
            var pollPayload = await pollRes.json();
            if (!pollRes.ok) {
                throw new Error(pollPayload.message || pollPayload.error || ('Skill poll failed with HTTP ' + pollRes.status));
            }

            var state = String(pollPayload.state || '').toUpperCase();
            if (state === 'COMPLETED') {
                return {
                    executionId: executionId,
                    outputContentType: pollPayload.outputContentType || 'application/json',
                    result: pollPayload.result
                };
            }
            if (state === 'FAILED') {
                throw new Error(pollPayload.error || 'Skill execution failed.');
            }
        }
    }

    async function getContracts(options) {
        options = options || {};
        var surfaceUuid = options.surfaceUuid || detectSurfaceUuid();
        if (!surfaceUuid) throw new Error('Cannot resolve surfaceUuid for skill contract lookup.');
        var response = await fetch('/api/surfaces/' + encodeURIComponent(surfaceUuid) + '/skill-contracts');
        var payload = await response.json();
        if (!response.ok) {
            throw new Error(payload.message || payload.error || ('Skill contract lookup failed with HTTP ' + response.status));
        }
        return payload;
    }

    window.vork = window.vork || {};
    window.vork.skills = {
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

    public record UpdateSurfaceRequest(String name,
                                       String description,
                                       List<String> skillUuids,
                                       List<String> reflectionBindingUuids,
                                       List<String> jobUuids,
                                       Boolean published,
                                       String logoDataUrl,
                                       List<String> assignedUserUuids,
                                       AccessPolicyRequest accessPolicy) {

        public Surface.AccessPolicy toAccessPolicy() {
            if (accessPolicy == null) {
                return null;
            }
            return new Surface.AccessPolicy(
                    accessPolicy.homeScreenEnabled(),
                    accessPolicy.navButtonEnabled(),
                    accessPolicy.navButtonIcon(),
                    accessPolicy.privateUrlEnabled(),
                    accessPolicy.privateUrlPath(),
                    accessPolicy.publicUrlEnabled(),
                    accessPolicy.publicUrlPath());
        }
    }

    public record AccessPolicyRequest(boolean homeScreenEnabled,
                                      boolean navButtonEnabled,
                                      String navButtonIcon,
                                      boolean privateUrlEnabled,
                                      String privateUrlPath,
                                      boolean publicUrlEnabled,
                                      String publicUrlPath) {
    }

    public record CreateSurfaceRequest(String name,
                                       String description,
                                       String groupId,
                                       String artifactId,
                                       Boolean published,
                                       String logoDataUrl,
                                       List<String> assignedUserUuids,
                                       AccessPolicyRequest accessPolicy) {

        public Surface.AccessPolicy toAccessPolicy() {
            if (accessPolicy == null) {
                return null;
            }
            return new Surface.AccessPolicy(
                    accessPolicy.homeScreenEnabled(),
                    accessPolicy.navButtonEnabled(),
                    accessPolicy.navButtonIcon(),
                    accessPolicy.privateUrlEnabled(),
                    accessPolicy.privateUrlPath(),
                    accessPolicy.publicUrlEnabled(),
                    accessPolicy.publicUrlPath());
        }
    }

    public record SurfaceReflectionInvokeRequest(String reflectionId,
                                                 String bindingGroupToolId,
                                                 String bindingProfileName,
                                                 Map<String, Object> args) {
    }

    public record SurfaceSkillInvokeRequest(String groupId,
                                            String skillId,
                                            Map<String, Object> args) {
    }

        @JsonIgnoreProperties(ignoreUnknown = true)
    public record SurfaceExportPackage(
            String vorkSurfaceExport,
                SurfaceArtifact surface
    ) {
    }

            @JsonIgnoreProperties(ignoreUnknown = true)
            public record SurfaceArtifact(
                String uuid,
                String name,
                String description,
                List<String> skillUuids,
                List<String> reflectionBindingUuids,
                List<String> jobUuids,
                boolean published,
                String logoDataUrl,
                List<String> assignedUserUuids,
                Surface.AccessPolicy accessPolicy,
                String groupId,
                String artifactId,
                String version,
                ArtifactStatus artifactStatus
            ) {
            }

    public record SurfaceImportResult(
            String status,
            String surfaceUuid,
            String message
    ) {
    }

    private static boolean hasImmutableSurfaceFieldChanges(Surface existing, UpdateSurfaceRequest req) {
        if (req == null) {
            return false;
        }

        if (req.name() != null && !req.name().isBlank() && !Objects.equals(req.name(), existing.name())) {
            return true;
        }
        if (req.description() != null && !Objects.equals(req.description(), existing.description())) {
            return true;
        }
        if (req.skillUuids() != null && !Objects.equals(req.skillUuids(), existing.skillUuids())) {
            return true;
        }
        if (req.reflectionBindingUuids() != null && !Objects.equals(req.reflectionBindingUuids(), existing.reflectionBindingUuids())) {
            return true;
        }
        if (req.jobUuids() != null && !Objects.equals(req.jobUuids(), existing.jobUuids())) {
            return true;
        }
        return req.logoDataUrl() != null && !Objects.equals(req.logoDataUrl(), existing.logoDataUrl());
    }

    private static String validateArtifactIdentity(String groupId, String artifactId, String version) {
        if (groupId == null || groupId.isBlank()) {
            return "groupId is required.";
        }
        if (artifactId == null || artifactId.isBlank()) {
            return "artifactId is required.";
        }
        if (groupId.length() < GROUP_ID_MIN_LEN || groupId.length() > GROUP_ID_MAX_LEN) {
            return "groupId length must be between 3 and 64 characters.";
        }
        if (artifactId.length() < ARTIFACT_ID_MIN_LEN || artifactId.length() > ARTIFACT_ID_MAX_LEN) {
            return "artifactId length must be between 3 and 64 characters.";
        }
        if (!GROUP_ID_PATTERN.matcher(groupId).matches()) {
            return "groupId must be alphanumeric only (letters and numbers), with no spaces.";
        }
        if (!ARTIFACT_ID_PATTERN.matcher(artifactId).matches()) {
            return "artifactId must be alphanumeric only (letters and numbers), with no spaces.";
        }
        if (version == null || version.isBlank()) {
            return "version is required.";
        }
        if (version.length() > VERSION_MAX_LEN) {
            return "version length must be 16 characters or fewer.";
        }
        if (!SNAPSHOT_VERSION.equals(version)) {
            return "Only version SNAPSHOT is supported in this flow.";
        }
        return null;
    }

    private static String toVid(String groupId, String artifactId, String version) {
        return groupId + "-" + artifactId + "-" + version;
    }

    private void collectSessionEntries(Map<String, byte[]> target,
                                       String sessionUuid,
                                       String zipPrefix) throws IOException {
        collectDirectoryEntries(target, sessionUuid, "", zipPrefix);
    }

    private void collectDirectoryEntries(Map<String, byte[]> target,
                                         String sessionUuid,
                                         String relativeDir,
                                         String zipPrefix) throws IOException {
        List<sh.vork.filesystem.FileNode> nodes = sessionFileSystem.list(FileArea.SESSION, sessionUuid, relativeDir);
        for (sh.vork.filesystem.FileNode node : nodes) {
            if (node == null) {
                continue;
            }
            if (node.directory()) {
                collectDirectoryEntries(target, sessionUuid, node.path(), zipPrefix);
                continue;
            }
            try (InputStream in = sessionFileSystem.read(FileArea.SESSION, sessionUuid, node.path())) {
                String entryPath = zipPrefix + "/" + node.path();
                target.put(entryPath, in.readAllBytes());
            }
        }
    }

    private void restoreSessionFiles(String targetSessionUuid,
                                     Map<String, byte[]> zipEntries,
                                     String zipPrefix) throws IOException {
        if (targetSessionUuid == null || targetSessionUuid.isBlank()) {
            return;
        }
        if (zipEntries == null || zipEntries.isEmpty()) {
            return;
        }

        for (Map.Entry<String, byte[]> entry : zipEntries.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().startsWith(zipPrefix)) {
                continue;
            }
            String relativePath = entry.getKey().substring(zipPrefix.length());
            if (relativePath.isBlank()) {
                continue;
            }
            byte[] content = entry.getValue() == null ? new byte[0] : entry.getValue();
            sessionFileSystem.write(
                    FileArea.SESSION,
                    targetSessionUuid,
                    relativePath,
                    new ByteArrayInputStream(content),
                    content.length);
        }
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
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript";
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
<script src="/surface/runtime/v1/skills.js"></script>
<script src="/js/surface-preview-console.js"></script>
""";

        int bodyClose = html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
        if (bodyClose >= 0) {
            return html.substring(0, bodyClose) + injection + html.substring(bodyClose);
        }
        return html + injection;
    }

    private static String injectPublishedRuntime(String html) {
        if (html == null || html.isBlank()) {
            return html;
        }

        // Published runtime should never load preview-only helpers.
        html = html.replace("/surface/runtime/v1/reflections.js", "/apps/runtime/v1/reflections.js");
        html = html.replace("/surface/runtime/v1/skills.js", "/apps/runtime/v1/skills.js");
        html = html.replace("/js/surface-preview-console.js", "");

        String injection = """
    <script src="/apps/runtime/v1/compat.js"></script>
<script src="/apps/runtime/v1/reflections.js"></script>
<script src="/apps/runtime/v1/skills.js"></script>
""";

        if (html.contains("/apps/runtime/v1/compat.js")
            && html.contains("/apps/runtime/v1/reflections.js")
            && html.contains("/apps/runtime/v1/skills.js")) {
            return html;
        }

        int bodyClose = html.toLowerCase(Locale.ROOT).lastIndexOf("</body>");
        if (bodyClose >= 0) {
            return html.substring(0, bodyClose) + injection + html.substring(bodyClose);
        }
        return html + injection;
    }

    private ResponseEntity<?> ensurePublishedSurfaceAccess(String uuid, Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", "error", "message", "Access denied"));
        }
        Surface surface = surfaceService.get(uuid);
        if (surface == null || !surface.published()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", "error", "message", "Surface not found"));
        }
        boolean assigned = surfaceService.isUserAssigned(surface, principal.getName());
        if (!assigned && !hasManageUsersPermission()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", "error", "message", "Access denied"));
        }
        return null;
    }

    private ResponseEntity<?> servePublishedSurfaceFile(Surface surface,
                                                        String assetPath,
                                                        String username) {
        if (surface == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Surface not found");
        }

        String sessionUuid = surface.sessionUuid();
        if (sessionUuid == null || sessionUuid.isBlank()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Surface not found");
        }

        String relativePath = normalizePublishedAssetPath(assetPath);
        try (InputStream in = sessionFileSystem.read(FileArea.SESSION, sessionUuid, relativePath)) {
            byte[] bytes = in.readAllBytes();
            String mime = probeMimeType(relativePath);
            if (mime.startsWith("text/html")) {
                String html = new String(bytes, StandardCharsets.UTF_8);
                html = injectPublishedRuntime(html);
                html = rewritePublishedRuntimePaths(html);
                bytes = html.getBytes(StandardCharsets.UTF_8);
            } else if (mime.equals("text/javascript") || mime.equals("application/javascript") || mime.equals("application/json")) {
                String content = new String(bytes, StandardCharsets.UTF_8);
                content = rewritePublishedRuntimePaths(content);
                bytes = content.getBytes(StandardCharsets.UTF_8);
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mime))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + escapeHeaderValue(fileName(relativePath)) + "\"")
                    .body(bytes);
        } catch (Exception ex) {
            log.warn("Published surface file read failed [surfaceUuid={}, path={}, user={}, reason={}]",
                    surface.uuid(), relativePath, username, ex.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Surface file not found");
        }
    }

    private static String normalizePublishedAssetPath(String assetPath) {
        if (assetPath == null) {
            return "index.html";
        }
        String normalized = assetPath.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank()) {
            return "index.html";
        }
        if (normalized.endsWith("/")) {
            return normalized + "index.html";
        }
        return normalized;
    }

    private static String rewritePublishedRuntimePaths(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        String rewritten = content;

        // Normalize runtime helper script paths for published surfaces.
        rewritten = rewritten.replace("/surface/runtime/v1/reflections.js", "/apps/runtime/v1/reflections.js");
        rewritten = rewritten.replace("surface/runtime/v1/reflections.js", "/apps/runtime/v1/reflections.js");
        rewritten = rewritten.replace("/surface/runtime/v1/skills.js", "/apps/runtime/v1/skills.js");
        rewritten = rewritten.replace("surface/runtime/v1/skills.js", "/apps/runtime/v1/skills.js");

        // Normalize reflection and skill API paths for published execution context.
        rewritten = rewritten.replace("/api/surfaces/", "/api/apps/published/");

        return rewritten;
    }

    private static boolean hasManageUsersPermission() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> "USERS_MANAGE".equals(a.getAuthority()));
    }
}
