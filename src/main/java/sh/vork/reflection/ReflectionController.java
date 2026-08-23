package sh.vork.reflection;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST management API for reflections.
 */
@RestController
@RequestMapping("/api")
@PreAuthorize("isAuthenticated()")
public class ReflectionController {

    private static final Logger log = LoggerFactory.getLogger(ReflectionController.class);
    private static final ObjectMapper EXPORT_OBJECT_MAPPER = new ObjectMapper();

    private final ReflectionService reflectionService;

    public ReflectionController(ReflectionService reflectionService) {
        this.reflectionService = reflectionService;
    }

    @GetMapping("/reflection-groups")
    public ResponseEntity<?> listGroups() {
        List<ReflectionGroupView> groups = reflectionService.listGroups().stream()
            .map(group -> new ReflectionGroupView(
                group,
                reflectionService.reflectionsForGroup(group.uuid()),
                reflectionService.bindingsForGroup(group.uuid())))
                .toList();
        return ResponseEntity.ok(groups);
    }

    @GetMapping("/reflection-groups/{uuid}")
    public ResponseEntity<?> getGroup(@PathVariable String uuid) {
        ReflectionGroup group = reflectionService.getGroup(uuid);
        if (group == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(new ReflectionGroupView(
                group,
                reflectionService.reflectionsForGroup(uuid),
                reflectionService.bindingsForGroup(uuid)));
    }

    @GetMapping("/reflection-groups/{groupUuid}/bindings")
    public ResponseEntity<?> listBindings(@PathVariable String groupUuid) {
        ReflectionGroup group = reflectionService.getGroup(groupUuid);
        if (group == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(reflectionService.bindingsForGroup(groupUuid));
    }

    @PostMapping("/reflection-groups/{groupUuid}/bindings")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> createBinding(@PathVariable String groupUuid,
                                           @RequestBody ReflectionService.ReflectionBindingRequest request) {
        try {
            ReflectionBinding created = reflectionService.createBinding(currentUsername(), groupUuid, request);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/reflection-groups/{groupUuid}/bindings/oauth-flow")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> saveBindingWithOAuthFlow(
            @PathVariable String groupUuid,
            @RequestBody OAuthBindingFlowRequest request) {
        try {
            if (request == null || request.bindingRequest() == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Binding payload is required."));
            }
            ReflectionService.BindingSaveOutcome outcome = reflectionService.saveBindingWithOAuthFlow(
                    currentUsername(),
                    groupUuid,
                    request.originalBindingName(),
                    request.bindingRequest(),
                    request.clientId(),
                    request.clientSecret(),
                    request.redirectUri());
            if ("not_found".equals(outcome.status())) {
                return ResponseEntity.status(404).body(Map.of("error", outcome.message()));
            }
            if ("error".equals(outcome.status())) {
                return ResponseEntity.badRequest().body(Map.of("error", outcome.message()));
            }
            return ResponseEntity.ok(outcome);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/reflection-groups/{groupUuid}/bindings/{bindingName}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> updateBinding(@PathVariable String groupUuid,
                                           @PathVariable String bindingName,
                                           @RequestBody ReflectionService.ReflectionBindingRequest request) {
        try {
            ReflectionBinding updated = reflectionService.updateBinding(currentUsername(), groupUuid, bindingName, request);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @DeleteMapping("/reflection-groups/{groupUuid}/bindings/{bindingName}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> deleteBinding(@PathVariable String groupUuid,
                                           @PathVariable String bindingName) {
        try {
            boolean deleted = reflectionService.deleteBinding(currentUsername(), groupUuid, bindingName);
            if (!deleted) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/reflection-groups")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> createGroup(@RequestBody ReflectionService.ReflectionGroupRequest request) {
        try {
            ReflectionGroup created = reflectionService.createGroup(request);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/reflection-groups/{uuid}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> updateGroup(@PathVariable String uuid,
                                         @RequestBody ReflectionService.ReflectionGroupRequest request) {
        try {
            ReflectionGroup updated = reflectionService.updateGroup(uuid, request);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @DeleteMapping("/reflection-groups/{uuid}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> deleteGroup(@PathVariable String uuid,
                                         @RequestParam(name = "purge", defaultValue = "false") boolean purge) {
        ReflectionService.GroupDeleteResult result = reflectionService.deleteGroup(currentUsername(), uuid, purge);
        if (!result.ok()) {
            return ResponseEntity.badRequest().body(Map.of("error", result.message()));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/reflection-groups/{uuid}/export")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> exportGroup(@PathVariable String uuid) {
        ReflectionService.ReflectionGroupExportPackage pkg = reflectionService.exportGroup(uuid);
        if (pkg == null) {
            return ResponseEntity.notFound().build();
        }

        String prettyJson;
        try {
            prettyJson = EXPORT_OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(pkg);
        } catch (JsonProcessingException ex) {
            log.warn("Reflection export JSON serialization failed [groupUuid={}]: {}", uuid, ex.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to serialize export payload."));
        }

        String safeName = pkg.group().name().replaceAll("[^a-zA-Z0-9._-]", "_");
        String filename = "reflection-group-" + safeName + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(prettyJson);
    }

    @PostMapping("/reflection-groups/import")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> importGroup(@RequestBody ReflectionService.ReflectionGroupExportPackage pkg) {
        ReflectionService.ReflectionGroupImportResult result = reflectionService.importGroup(pkg);
        if ("error".equals(result.status())) {
            return ResponseEntity.badRequest().body(result);
        }
        if ("already_installed".equals(result.status())) {
            return ResponseEntity.ok(result);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/reflection-groups/mongo/inspect-connection")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> inspectMongoConnection(@RequestBody ReflectionService.MongoConnectionRequest request) {
        try {
            return ResponseEntity.ok(reflectionService.inspectMongoConnection(request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/reflection-groups/mongo/inspect-database")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> inspectMongoDatabase(@RequestBody ReflectionService.MongoDatabaseInspectRequest request) {
        try {
            return ResponseEntity.ok(reflectionService.inspectMongoDatabase(request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/reflection-groups/mongo/wizard-generate")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> generateMongoReflections(@RequestBody ReflectionService.MongoWizardGenerateRequest request) {
        try {
            return ResponseEntity.ok(reflectionService.generateMongoReflectionsFromWizard(currentUsername(), request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/mongo-reflections")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> createMongoReflection(@RequestBody ReflectionService.MongoToolRequest request) {
        try {
            Reflection created = reflectionService.createMongoToolReflection(request);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/mongo-reflections/{uuid}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> updateMongoReflection(@PathVariable String uuid,
                                                   @RequestBody ReflectionService.MongoToolRequest request) {
        try {
            Reflection updated = reflectionService.updateMongoToolReflection(uuid, request);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/reflections")
    public ResponseEntity<?> listReflections(@RequestParam(name = "groupUuid", required = false) String groupUuid) {
        if (groupUuid != null && !groupUuid.isBlank()) {
            return ResponseEntity.ok(reflectionService.reflectionsForGroup(groupUuid));
        }
        return ResponseEntity.ok(reflectionService.listReflections());
    }

    @GetMapping("/reflections/{uuid}")
    public ResponseEntity<?> getReflection(@PathVariable String uuid) {
        Reflection reflection = reflectionService.getReflection(uuid);
        if (reflection == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(reflection);
    }

    @GetMapping("/reflections/by-id/{id}")
    public ResponseEntity<?> getReflectionById(@PathVariable String id) {
        Reflection reflection = reflectionService.getReflectionById(id);
        if (reflection == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(reflection);
    }

    @PostMapping("/reflections")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> createReflection(@RequestBody ReflectionService.ReflectionRequest request) {
        try {
            Reflection created = reflectionService.createReflection(request);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/reflections/{uuid}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> updateReflection(@PathVariable String uuid,
                                              @RequestBody ReflectionService.ReflectionRequest request) {
        try {
            Reflection updated = reflectionService.updateReflection(uuid, request);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @DeleteMapping("/reflections/{uuid}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> deleteReflection(@PathVariable String uuid) {
        try {
            boolean deleted = reflectionService.deleteReflection(uuid);
            if (!deleted) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/reflections/search-execute")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> executeSearchReflection(@RequestBody SearchToolExecutionRequest request) {
        try {
            if (request == null || request.reflectionId() == null || request.reflectionId().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "reflectionId is required."));
            }
            String raw = reflectionService.executeRestReflection(
                    request.reflectionId().trim(),
                    request.args() == null ? Map.of() : request.args(),
                    request.bindingName(),
                    currentUsername());
            Object payload = EXPORT_OBJECT_MAPPER.readValue(raw, Object.class);
            return ResponseEntity.ok(payload);
        } catch (JsonProcessingException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to parse reflection response."));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleMalformedImportJson(HttpMessageNotReadableException ex) {
        Throwable root = ex.getMostSpecificCause();
        String detail = root != null && root.getMessage() != null ? root.getMessage() : ex.getMessage();

        log.warn("Reflection import JSON parse failure: {}", detail, ex);

        return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Invalid JSON payload for reflection-group import.",
                "detail", detail
        ));
    }

    private String currentUsername() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()) {
            throw new IllegalArgumentException("Authenticated user is required.");
        }
        return auth.getName();
    }

    public record ReflectionGroupView(
            ReflectionGroup group,
            List<Reflection> reflections,
            List<ReflectionBinding> bindings) {}

    public record SearchToolExecutionRequest(
            String reflectionId,
            String bindingName,
            Map<String, Object> args) {}

        public record OAuthBindingFlowRequest(
            String originalBindingName,
            ReflectionService.ReflectionBindingRequest bindingRequest,
            String clientId,
            String clientSecret,
            String redirectUri) {}
}
