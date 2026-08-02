package sh.vork.reflection;

import java.util.List;
import java.util.Map;

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
    public ResponseEntity<?> deleteGroup(@PathVariable String uuid) {
        ReflectionService.GroupDeleteResult result = reflectionService.deleteGroup(uuid);
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

        String safeName = pkg.group().name().replaceAll("[^a-zA-Z0-9._-]", "_");
        String filename = "reflection-group-" + safeName + ".json";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(pkg);
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
        boolean deleted = reflectionService.deleteReflection(uuid);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("ok", true));
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
}
