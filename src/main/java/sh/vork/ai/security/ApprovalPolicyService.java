package sh.vork.ai.security;

import org.springframework.stereotype.Service;
import sh.vork.orm.DatabaseRepository;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

@Service
public class ApprovalPolicyService {

    private final DatabaseRepository<ApprovalPolicy> approvalPolicyRepository;
    private final DatabaseRepository<ApprovalPolicyAssignment> assignmentRepository;

    public ApprovalPolicyService(DatabaseRepository<ApprovalPolicy> approvalPolicyRepository,
                                 DatabaseRepository<ApprovalPolicyAssignment> assignmentRepository) {
        this.approvalPolicyRepository = approvalPolicyRepository;
        this.assignmentRepository = assignmentRepository;
    }

    public List<ApprovalPolicy> listPolicies() {
        try (var stream = approvalPolicyRepository.list(0, Integer.MAX_VALUE)) {
            return stream.sorted(Comparator.comparing(ApprovalPolicy::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }
    }

    public ApprovalPolicy getPolicy(String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return null;
        }
        return approvalPolicyRepository.get(policyId.trim());
    }

    public ApprovalPolicy getPolicyByName(String policyName) {
        if (policyName == null || policyName.isBlank()) {
            return null;
        }

        String normalized = policyName.trim().toLowerCase(Locale.ROOT);
        List<ApprovalPolicy> matches;
        try (var stream = approvalPolicyRepository.list(0, Integer.MAX_VALUE)) {
            matches = stream
                    .filter(v -> v != null && v.name() != null)
                    .filter(v -> v.name().trim().toLowerCase(Locale.ROOT).equals(normalized))
                    .toList();
        }

        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Multiple policies share the same name: " + policyName);
        }
        return matches.getFirst();
    }

    public ApprovalPolicy createPolicy(ApprovalPolicy candidate) {
        ApprovalPolicy normalized = normalizePolicy(candidate, null);
        approvalPolicyRepository.save(normalized);
        return normalized;
    }

    public ApprovalPolicy updatePolicy(String policyId, ApprovalPolicy candidate) {
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("policyId is required");
        }
        ApprovalPolicy existing = approvalPolicyRepository.get(policyId.trim());
        if (existing == null) {
            throw new IllegalArgumentException("Policy not found: " + policyId);
        }
        ApprovalPolicy normalized = normalizePolicy(candidate, existing);
        approvalPolicyRepository.save(normalized);
        return normalized;
    }

    public void deletePolicy(String policyId) {
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("policyId is required");
        }
        String normalizedId = policyId.trim();
        approvalPolicyRepository.delete(normalizedId);

        try (var stream = assignmentRepository.list(0, Integer.MAX_VALUE)) {
            stream.filter(v -> normalizedId.equals(v.policyId()))
                    .forEach(v -> assignmentRepository.delete(v.uuid()));
        }
    }

    public void deletePolicyByName(String policyName) {
        if (policyName == null || policyName.isBlank()) {
            throw new IllegalArgumentException("policy name is required");
        }

        String normalizedName = policyName.trim().toLowerCase(Locale.ROOT);
        List<ApprovalPolicy> matches;
        try (var stream = approvalPolicyRepository.list(0, Integer.MAX_VALUE)) {
            matches = stream
                    .filter(v -> v != null && v.name() != null && v.name().trim().toLowerCase(Locale.ROOT).equals(normalizedName))
                    .toList();
        }

        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Policy not found: " + policyName);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Multiple policies share the same name. Delete by id instead.");
        }

        ApprovalPolicy match = matches.get(0);
        if (match.uuid() == null || match.uuid().isBlank()) {
            throw new IllegalArgumentException("Policy has no id. Please recreate and then delete it.");
        }
        deletePolicy(match.uuid());
    }

    public Map<String, String> listAssignmentsByTargetType(String targetType) {
        String normalizedType = normalizeTargetType(targetType);
        Map<String, String> assignments = new HashMap<>();
        try (var stream = assignmentRepository.list(0, Integer.MAX_VALUE)) {
            stream.filter(v -> normalizedType.equals(v.targetType()))
                    .forEach(v -> {
                        if (v.targetId() != null && !v.targetId().isBlank()) {
                            assignments.put(v.targetId(), v.policyId());
                        }
                    });
        }
        return assignments;
    }

    public void assignPolicy(String targetType, String targetId, String policyId) {
        String normalizedType = normalizeTargetType(targetType);
        if (targetId == null || targetId.isBlank()) {
            throw new IllegalArgumentException("targetId is required");
        }
        String normalizedTargetId = targetId.trim();
        String assignmentId = ApprovalPolicyAssignment.key(normalizedType, normalizedTargetId);

        if (policyId == null || policyId.isBlank()) {
            assignmentRepository.delete(assignmentId);
            return;
        }

        String normalizedPolicyId = policyId.trim();
        ApprovalPolicy target = approvalPolicyRepository.get(normalizedPolicyId);
        if (target == null) {
            throw new IllegalArgumentException("Unknown approval policy: " + normalizedPolicyId);
        }

        assignmentRepository.save(new ApprovalPolicyAssignment(
                assignmentId,
                normalizedType,
                normalizedTargetId,
                normalizedPolicyId,
                System.currentTimeMillis()
        ));
    }

    private ApprovalPolicy normalizePolicy(ApprovalPolicy candidate, ApprovalPolicy existing) {
        if (candidate == null) {
            throw new IllegalArgumentException("policy payload is required");
        }
        if (candidate.name() == null || candidate.name().isBlank()) {
            throw new IllegalArgumentException("policy name is required");
        }
        String normalizedName = candidate.name().trim();
        ensureUniquePolicyName(normalizedName, existing == null ? null : existing.uuid());
        if (candidate.channels() == null || candidate.channels().isEmpty()) {
            throw new IllegalArgumentException("policy channels must contain at least one channel");
        }

        long now = System.currentTimeMillis();
        String id = existing == null
                ? UUID.randomUUID().toString()
                : existing.uuid();
        long createdAt = existing == null ? now : existing.createdAt();

        return new ApprovalPolicy(
                id,
                normalizedName,
                candidate.enabled(),
                candidate.channels(),
                candidate.overrides(),
                createdAt,
                now
        );
    }

    private void ensureUniquePolicyName(String name, String ignorePolicyId) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        try (var stream = approvalPolicyRepository.list(0, Integer.MAX_VALUE)) {
            boolean duplicate = stream
                    .filter(v -> v != null && v.name() != null)
                    .anyMatch(v -> {
                        if (ignorePolicyId != null && ignorePolicyId.equals(v.uuid())) {
                            return false;
                        }
                        return v.name().trim().toLowerCase(Locale.ROOT).equals(normalized);
                    });
            if (duplicate) {
                throw new IllegalArgumentException("policy name must be unique: " + name);
            }
        }
    }

    private static String normalizeTargetType(String targetType) {
        if (targetType == null || targetType.isBlank()) {
            throw new IllegalArgumentException("targetType is required");
        }
        String normalized = targetType.trim().toLowerCase();
        if (!ApprovalPolicyAssignment.TARGET_AGENT.equals(normalized)
                && !ApprovalPolicyAssignment.TARGET_SKILL.equals(normalized)) {
            throw new IllegalArgumentException("Unsupported targetType: " + targetType);
        }
        return normalized;
    }
}
