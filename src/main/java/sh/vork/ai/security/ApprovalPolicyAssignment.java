package sh.vork.ai.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import sh.vork.orm.DatabaseEntity;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalPolicyAssignment(
        String uuid,
        String targetType,
        String targetId,
        String policyId,
        long updatedAt
) implements DatabaseEntity {

    public static final String TARGET_AGENT = "agent";
    public static final String TARGET_SKILL = "skill";

    public ApprovalPolicyAssignment {
        if (targetType == null) {
            targetType = "";
        } else {
            targetType = targetType.trim().toLowerCase();
        }
        if (targetId == null) {
            targetId = "";
        } else {
            targetId = targetId.trim();
        }
        if (policyId == null) {
            policyId = "";
        } else {
            policyId = policyId.trim();
        }
    }

    public static String key(String targetType, String targetId) {
        return (targetType == null ? "" : targetType.trim().toLowerCase()) + ":" + (targetId == null ? "" : targetId.trim());
    }
}
