package sh.vork.ai.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalPolicyOverride(
        String day,
        String startTime,
        String endTime,
        List<String> channels,
        boolean enabled
) {

    public ApprovalPolicyOverride {
        if (day == null || day.isBlank()) {
            day = "";
        } else {
            day = day.trim().toUpperCase();
        }
        if (startTime == null) {
            startTime = "";
        } else {
            startTime = startTime.trim();
        }
        if (endTime == null) {
            endTime = "";
        } else {
            endTime = endTime.trim();
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
    }
}
