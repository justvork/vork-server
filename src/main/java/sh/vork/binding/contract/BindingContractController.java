package sh.vork.binding.contract;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/binding-contracts")
@PreAuthorize("isAuthenticated()")
public class BindingContractController {

    private static final Logger log = LoggerFactory.getLogger(BindingContractController.class);

    private final BindingContractService service;

    public BindingContractController(BindingContractService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> listContracts() {
        log.debug("ENTER listContracts");
        List<BindingContract> contracts = service.listContracts();
        log.debug("EXIT listContracts: count={}", contracts.size());
        return ResponseEntity.ok(contracts);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getContract(@PathVariable String id) {
        log.debug("ENTER getContract: id={}", id);
        BindingContract contract = service.getContract(id);
        if (contract == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(contract);
    }

    @GetMapping("/{id}/tools")
    public ResponseEntity<?> listTools(@PathVariable String id) {
        log.debug("ENTER listTools: id={}", id);
        try {
            return ResponseEntity.ok(service.listTools(id));
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("Binding contract not found:")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/tools")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> addTool(@PathVariable String id,
                                     @RequestBody BindingContractToolDefinition tool) {
        log.debug("ENTER addTool: id={}, tool={}", id, tool == null ? null : tool.name());
        try {
            BindingContract updated = service.addTool(id, tool);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("Binding contract not found:")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/{id}/tools/{toolName}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> updateTool(@PathVariable String id,
                                        @PathVariable String toolName,
                                        @RequestBody BindingContractToolDefinition tool) {
        log.debug("ENTER updateTool: id={}, toolName={}", id, toolName);
        try {
            BindingContract updated = service.updateTool(id, toolName, tool);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("Binding contract not found:")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}/tools/{toolName}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> deleteTool(@PathVariable String id,
                                        @PathVariable String toolName) {
        log.debug("ENTER deleteTool: id={}, toolName={}", id, toolName);
        try {
            BindingContract updated = service.deleteTool(id, toolName);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            if (ex.getMessage() != null && ex.getMessage().startsWith("Binding contract not found:")) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> createContract(@RequestBody BindingContract request) {
        log.debug("ENTER createContract: name={}", request == null ? null : request.name());
        try {
            BindingContract created = service.createContract(request);
            return ResponseEntity.ok(created);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> updateContract(@PathVariable String id,
                                            @RequestBody BindingContract request) {
        log.debug("ENTER updateContract: id={}", id);
        try {
            BindingContract updated = service.updateContract(id, request);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> deleteContract(@PathVariable String id) {
        log.debug("ENTER deleteContract: id={}", id);
        try {
            boolean deleted = service.deleteContract(id);
            if (!deleted) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> submitContract(@PathVariable String id) {
        log.debug("ENTER submitContract: id={}", id);
        try {
            BindingContract updated = service.markSubmitted(id);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/stage")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> stageContract(@PathVariable String id) {
        log.debug("ENTER stageContract: id={}", id);
        try {
            BindingContract updated = service.markStaged(id);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> publishContract(@PathVariable String id) {
        log.debug("ENTER publishContract: id={}", id);
        try {
            BindingContract updated = service.markPublished(id);
            if (updated == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> exportContract(@PathVariable String id) {
        log.debug("ENTER exportContract: id={}", id);
        BindingContractService.BindingContractExportPackage pkg = service.exportContract(id);
        if (pkg == null || pkg.contract() == null) {
            return ResponseEntity.notFound().build();
        }

        String safeName = pkg.contract().name() == null
                ? "binding-contract"
                : pkg.contract().name().replaceAll("[^a-zA-Z0-9._-]", "_");
        String filename = "binding-contract-" + safeName + ".json";

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(pkg);
    }

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> importContract(@RequestBody BindingContractService.BindingContractExportPackage pkg) {
        log.debug("ENTER importContract");
        BindingContractService.BindingContractImportResult result = service.importContract(pkg);
        if ("error".equalsIgnoreCase(result.status())) {
            return ResponseEntity.badRequest().body(result);
        }
        return ResponseEntity.ok(result);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleMalformedImportJson(HttpMessageNotReadableException ex) {
        Throwable root = ex.getMostSpecificCause();
        String detail = root != null && root.getMessage() != null ? root.getMessage() : ex.getMessage();

        log.warn("Binding contract import JSON parse failure: {}", detail, ex);

        return ResponseEntity.badRequest().body(Map.of(
                "status", "error",
                "message", "Invalid JSON payload for binding contract import.",
                "detail", detail
        ));
    }
}
