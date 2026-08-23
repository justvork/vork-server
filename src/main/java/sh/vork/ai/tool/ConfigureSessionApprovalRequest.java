package sh.vork.ai.tool;

import java.util.List;

public record ConfigureSessionApprovalRequest(
        Boolean enabled,
        Boolean clear,
        String policyId,
        String policyName,
        List<String> channelNames,
        String responsePolicy,
        Integer quorum,
        String reason
) {
}
