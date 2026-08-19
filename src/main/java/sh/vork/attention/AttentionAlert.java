package sh.vork.attention;

import sh.vork.orm.DatabaseEntity;

import java.util.List;

public record AttentionAlert(
        String uuid,
        List<String> channelNames,
        String alertName,
        String description,
        AttentionResolutionPolicy resolutionPolicy,
        String actionUrl,
        long attentionAt,
        AttentionSourceType sourceType,
        String sourceId,
        long createdAt,
        long updatedAt
) implements DatabaseEntity {

    public AttentionAlert {
        if (channelNames == null) {
            channelNames = List.of();
        }
        if (alertName == null) {
            alertName = "";
        }
        if (description == null) {
            description = "";
        }
        if (actionUrl == null) {
            actionUrl = "";
        }
        if (sourceId == null) {
            sourceId = "";
        }
        if (resolutionPolicy == null) {
            resolutionPolicy = AttentionResolutionPolicy.DISMISSABLE;
        }
        if (sourceType == null) {
            sourceType = AttentionSourceType.CUSTOM;
        }
    }
}
