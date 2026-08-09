package sh.vork.binding.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sh.vork.binding.BindingCatalogService;
import sh.vork.binding.BindingInvocationRequest;
import sh.vork.binding.BindingInvocationResult;
import sh.vork.binding.BindingOperationContract;
import sh.vork.binding.BindingSummary;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic binding catalog and invocation API.
 */
@RestController
@RequestMapping("/api/bindings")
public class BindingController {

    private static final Logger log = LoggerFactory.getLogger(BindingController.class);

    private final BindingCatalogService bindingCatalogService;

    public BindingController(BindingCatalogService bindingCatalogService) {
        this.bindingCatalogService = bindingCatalogService;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public List<BindingSummary> listBindings() {
        log.debug("ENTER listBindings");
        return bindingCatalogService.listBindings();
    }

    @GetMapping(value = "/{bindingId}/contracts", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public List<BindingOperationContract> listContracts(@PathVariable String bindingId,
                                                        @RequestParam(defaultValue = "default") String profile) {
        log.debug("ENTER listContracts: [bindingId={}, profile={}]", bindingId, profile);
        return bindingCatalogService.listOperationContracts(bindingId, profile);
    }

    @PostMapping(value = "/invoke", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> invoke(@RequestBody InvokeRequest req, Principal principal) {
        log.debug("ENTER invoke: [bindingId={}, profile={}, operation={}]",
                req == null ? null : req.bindingId(),
                req == null ? null : req.profile(),
                req == null ? null : req.operationId());
        if (req == null || req.bindingId() == null || req.bindingId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "bindingId is required"));
        }
        if (req.operationId() == null || req.operationId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", "operationId is required"));
        }

        try {
            BindingInvocationResult result = bindingCatalogService.invoke(new BindingInvocationRequest(
                    req.bindingId().trim(),
                    req.profile(),
                    req.operationId().trim(),
                    req.args(),
                    principal == null ? "system" : principal.getName()));

            return ResponseEntity.status(result.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(result.body());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    public record InvokeRequest(
            String bindingId,
            String profile,
            String operationId,
            Map<String, Object> args
    ) {}
}
