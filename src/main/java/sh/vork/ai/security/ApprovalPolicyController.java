package sh.vork.ai.security;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/approval-policies")
public class ApprovalPolicyController {

    private final ApprovalPolicyService approvalPolicyService;

    public ApprovalPolicyController(ApprovalPolicyService approvalPolicyService) {
        this.approvalPolicyService = approvalPolicyService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public List<ApprovalPolicy> listPolicies() {
        return approvalPolicyService.listPolicies();
    }

    @GetMapping("/{policyId}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> getPolicy(@PathVariable String policyId) {
        ApprovalPolicy policy = approvalPolicyService.getPolicy(policyId);
        if (policy == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(policy);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> createPolicy(@RequestBody ApprovalPolicy policy) {
        try {
            return ResponseEntity.ok(approvalPolicyService.createPolicy(policy));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/{policyId}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> updatePolicy(@PathVariable String policyId,
                                          @RequestBody ApprovalPolicy policy) {
        try {
            return ResponseEntity.ok(approvalPolicyService.updatePolicy(policyId, policy));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @DeleteMapping("/{policyId}")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> deletePolicy(@PathVariable String policyId) {
        try {
            approvalPolicyService.deletePolicy(policyId);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @DeleteMapping
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> deletePolicyByQuery(@RequestParam(value = "policyId", required = false) String policyId,
                                                 @RequestParam(value = "name", required = false) String name) {
        try {
            if (policyId != null && !policyId.isBlank()) {
                approvalPolicyService.deletePolicy(policyId);
                return ResponseEntity.ok(Map.of("ok", true));
            }
            if (name != null && !name.isBlank()) {
                approvalPolicyService.deletePolicyByName(name);
                return ResponseEntity.ok(Map.of("ok", true));
            }
            return ResponseEntity.badRequest().body(Map.of("error", "policyId or name is required"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/assignments")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> listAssignments(@RequestParam("targetType") String targetType) {
        try {
            return ResponseEntity.ok(approvalPolicyService.listAssignmentsByTargetType(targetType));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PutMapping("/assignments")
    @PreAuthorize("hasAuthority('USERS_MANAGE')")
    public ResponseEntity<?> upsertAssignment(@RequestBody ApprovalPolicyAssignmentRequest request) {
        try {
            approvalPolicyService.assignPolicy(request.targetType(), request.targetId(), request.policyId());
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    public record ApprovalPolicyAssignmentRequest(
            String targetType,
            String targetId,
            String policyId
    ) {}
}
