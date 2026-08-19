package sh.vork.mcp.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import sh.vork.mcp.controller.dto.McpBindingResponse;
import sh.vork.mcp.controller.dto.McpBindingUpsertRequest;
import sh.vork.mcp.controller.dto.McpDriftReportResponse;
import sh.vork.mcp.controller.dto.McpToolUpdateRequest;
import sh.vork.mcp.model.McpBinding;
import sh.vork.mcp.service.McpBindingService;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mcp/bindings")
public class McpBindingController {

    private static final Logger log = LoggerFactory.getLogger(McpBindingController.class);

    private final McpBindingService bindingService;

    public McpBindingController(McpBindingService bindingService) {
        this.bindingService = bindingService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public List<McpBindingResponse> list() {
        log.debug("ENTER listMcpBindings");
        List<McpBindingResponse> results = bindingService.list().stream()
                .map(this::toResponse)
                .toList();
        log.debug("EXIT listMcpBindings: count={}", results.size());
        return results;
    }

    @GetMapping("/{uuid}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> get(@PathVariable String uuid) {
        log.debug("ENTER getMcpBinding: uuid={}", uuid);
        McpBinding binding = bindingService.get(uuid);
        if (binding == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toResponse(binding));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> create(@RequestBody McpBindingUpsertRequest request) {
        log.debug("ENTER createMcpBinding: name={}", request.name());
        try {
            McpBinding binding = bindingService.createOrUpdate(toRequest(request), null);
            return ResponseEntity.ok(toResponse(binding));
        } catch (IllegalArgumentException ex) {
            log.warn("MCP binding create validation failed [name={}, error={}]", request.name(), ex.getMessage());
            return ResponseEntity.badRequest().body(error("MCP_VALIDATE_FAILED", ex.getMessage()));
        } catch (IllegalStateException ex) {
            log.warn("MCP binding create upstream failed [name={}, error={}]", request.name(), ex.getMessage());
            return ResponseEntity.status(502).body(error("MCP_UPSTREAM_FAILED", ex.getMessage()));
        }
    }

    @PutMapping("/{uuid}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> update(@PathVariable String uuid, @RequestBody McpBindingUpsertRequest request) {
        log.debug("ENTER updateMcpBinding: uuid={}", uuid);
        try {
            McpBinding binding = bindingService.createOrUpdate(toRequest(request), uuid);
            return ResponseEntity.ok(toResponse(binding));
        } catch (IllegalArgumentException ex) {
            log.warn("MCP binding update validation failed [uuid={}, error={}]", uuid, ex.getMessage());
            return ResponseEntity.badRequest().body(error("MCP_VALIDATE_FAILED", ex.getMessage()));
        } catch (IllegalStateException ex) {
            log.warn("MCP binding update upstream failed [uuid={}, error={}]", uuid, ex.getMessage());
            return ResponseEntity.status(502).body(error("MCP_UPSTREAM_FAILED", ex.getMessage()));
        }
    }

    @DeleteMapping("/{uuid}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> delete(@PathVariable String uuid) {
        log.debug("ENTER deleteMcpBinding: uuid={}", uuid);
        try {
            bindingService.delete(uuid);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error("MCP_BINDING_NOT_FOUND", ex.getMessage()));
        }
    }

    @PostMapping("/{uuid}/discover")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> discover(@PathVariable String uuid) {
        log.debug("ENTER discoverMcpBinding: uuid={}", uuid);
        try {
            var sync = bindingService.sync(uuid);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "contractHash", sync.snapshot().contractHash(),
                    "discoveredAt", sync.snapshot().discoveredAt(),
                    "tools", sync.snapshot().discoverResult().tools().size(),
                    "resources", sync.snapshot().discoverResult().resources().size(),
                    "prompts", sync.snapshot().discoverResult().prompts().size(),
                    "changed", sync.changed(),
                    "statusAfterSync", sync.statusAfterSync().name(),
                    "diff", sync.diff()));
        } catch (IllegalArgumentException ex) {
            log.warn("MCP discover failed: binding not found [uuid={}, error={}]", uuid, ex.getMessage());
            return ResponseEntity.badRequest().body(error("MCP_BINDING_NOT_FOUND", ex.getMessage()));
        } catch (IllegalStateException ex) {
            log.warn("MCP discover upstream failed [uuid={}, error={}]", uuid, ex.getMessage());
            return ResponseEntity.status(502).body(error("MCP_UPSTREAM_FAILED", ex.getMessage()));
        }
    }

    @PostMapping("/{uuid}/sync")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> sync(@PathVariable String uuid) {
        log.debug("ENTER syncMcpBinding: uuid={}", uuid);
        try {
            var sync = bindingService.sync(uuid);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "contractHash", sync.snapshot().contractHash(),
                    "discoveredAt", sync.snapshot().discoveredAt(),
                    "tools", sync.snapshot().discoverResult().tools().size(),
                    "resources", sync.snapshot().discoverResult().resources().size(),
                    "prompts", sync.snapshot().discoverResult().prompts().size(),
                    "changed", sync.changed(),
                    "statusAfterSync", sync.statusAfterSync().name(),
                    "diff", sync.diff()));
        } catch (IllegalArgumentException ex) {
            log.warn("MCP sync failed: binding not found [uuid={}, error={}]", uuid, ex.getMessage());
            return ResponseEntity.badRequest().body(error("MCP_BINDING_NOT_FOUND", ex.getMessage()));
        } catch (IllegalStateException ex) {
            log.warn("MCP sync upstream failed [uuid={}, error={}]", uuid, ex.getMessage());
            return ResponseEntity.status(502).body(error("MCP_UPSTREAM_FAILED", ex.getMessage()));
        }
    }

    @PostMapping("/{uuid}/activate")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> activate(@PathVariable String uuid) {
        log.debug("ENTER activateMcpBinding: uuid={}", uuid);
        try {
            McpBinding updated = bindingService.activate(uuid);
            return ResponseEntity.ok(toResponse(updated));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error("MCP_BINDING_NOT_FOUND", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(409).body(error("MCP_ACTIVATION_BLOCKED", ex.getMessage()));
        }
    }

    @PostMapping("/{uuid}/deactivate")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> deactivate(@PathVariable String uuid) {
        log.debug("ENTER deactivateMcpBinding: uuid={}", uuid);
        try {
            McpBinding updated = bindingService.deactivate(uuid);
            return ResponseEntity.ok(toResponse(updated));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error("MCP_BINDING_NOT_FOUND", ex.getMessage()));
        }
    }

    @PostMapping("/{uuid}/validate")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> validate(@PathVariable String uuid, @RequestBody McpBindingUpsertRequest request) {
        log.debug("ENTER validateMcpBinding: uuid={}, name={}", uuid, request.name());
        try {
            var snapshot = bindingService.validate(toRequest(request));
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "contractHash", snapshot.contractHash(),
                    "discoveredAt", snapshot.discoveredAt(),
                    "tools", snapshot.discoverResult().tools().size(),
                    "resources", snapshot.discoverResult().resources().size(),
                    "prompts", snapshot.discoverResult().prompts().size()));
        } catch (IllegalArgumentException ex) {
            log.warn("MCP validate failed [uuid={}, name={}, error={}]", uuid, request.name(), ex.getMessage());
            return ResponseEntity.badRequest().body(error("MCP_VALIDATE_FAILED", ex.getMessage()));
        } catch (IllegalStateException ex) {
            log.warn("MCP validate upstream failed [uuid={}, name={}, error={}]", uuid, request.name(), ex.getMessage());
            return ResponseEntity.status(502).body(error("MCP_UPSTREAM_FAILED", ex.getMessage()));
        }
    }

    @GetMapping("/{uuid}/tools")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> listTools(@PathVariable String uuid) {
        try {
            return ResponseEntity.ok(bindingService.listTools(uuid));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error("MCP_BINDING_NOT_FOUND", ex.getMessage()));
        }
    }

    @PutMapping("/{uuid}/tools/{toolId}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> updateTool(@PathVariable String uuid,
                                        @PathVariable String toolId,
                                        @RequestBody McpToolUpdateRequest request) {
        log.debug("ENTER updateMcpTool: bindingUuid={}, toolId={}", uuid, toolId);
        try {
            return ResponseEntity.ok(bindingService.updateToolConfig(uuid, toolId, request, resolveUsername()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error("MCP_TOOL_NOT_FOUND", ex.getMessage()));
        }
    }

    @GetMapping("/{uuid}/resources")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> listResources(@PathVariable String uuid) {
        try {
            return ResponseEntity.ok(bindingService.listResources(uuid));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error("MCP_BINDING_NOT_FOUND", ex.getMessage()));
        }
    }

    @GetMapping("/{uuid}/prompts")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> listPrompts(@PathVariable String uuid) {
        try {
            return ResponseEntity.ok(bindingService.listPrompts(uuid));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error("MCP_BINDING_NOT_FOUND", ex.getMessage()));
        }
    }

    @GetMapping("/{uuid}/drift")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> drift(@PathVariable String uuid) {
        try {
            var inspection = bindingService.inspectDrift(uuid);

            return ResponseEntity.ok(McpDriftReportResponse.of(
                inspection.bindingUuid(),
                inspection.previousHash(),
                inspection.currentHash(),
                inspection.diff()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(error("MCP_BINDING_NOT_FOUND", ex.getMessage()));
        }
    }

    private McpBindingResponse toResponse(McpBinding binding) {
        long toolCount = bindingService.listTools(binding.uuid()).size();
        long resourceCount = bindingService.listResources(binding.uuid()).size();
        long promptCount = bindingService.listPrompts(binding.uuid()).size();
        return McpBindingResponse.from(binding, toolCount, resourceCount, promptCount);
    }

    private McpBindingService.CreateOrUpdateRequest toRequest(McpBindingUpsertRequest request) {
        return new McpBindingService.CreateOrUpdateRequest(
                request.name(),
                request.baseUrl(),
                request.transportMode(),
                request.authorization(),
                request.groupId(),
                request.artifactId(),
                request.version(),
                request.artifactStatus());
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of(
                "status", "error",
                "code", code,
                "message", message,
                "timestamp", System.currentTimeMillis());
    }

    private static String resolveUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || auth.getName().isBlank()
                || "anonymousUser".equalsIgnoreCase(auth.getName())) {
            return "system";
        }
        return auth.getName();
    }
}
