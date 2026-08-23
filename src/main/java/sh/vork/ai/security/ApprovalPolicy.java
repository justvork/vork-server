package sh.vork.ai.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import sh.vork.orm.DatabaseEntity;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalPolicy(
        String uuid,
        String name,
        boolean enabled,
        List<String> channels,
        List<ApprovalPolicyOverride> overrides,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public ApprovalPolicy {
        if (name == null || name.isBlank()) {
            name = "Unnamed Policy";
        }
        if (channels == null) {
            channels = List.of();
        } else {
            channels = channels.stream()
                    .filter(v -> v != null && !v.isBlank())
                    .map(String::trim)
                    .distinct()
                    .toList();
        }
        if (overrides == null) {
            overrides = List.of();
        } else {
            overrides = overrides.stream()
                    .filter(v -> v != null)
                    .toList();
        }
        if (createdAt < 1) {
            createdAt = System.currentTimeMillis();
        }
        if (updatedAt < 1) {
            updatedAt = createdAt;
        }
    }
}
