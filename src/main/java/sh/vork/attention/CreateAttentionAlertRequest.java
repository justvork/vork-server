package sh.vork.attention;

import java.util.List;

public record CreateAttentionAlertRequest(
        List<String> channelNames,
        String alertName,
        String description,
        AttentionResolutionPolicy resolutionPolicy,
        String actionUrl,
        Long attentionAt,
        AttentionSourceType sourceType,
        String sourceId
) {
}
