package sh.vork.ai.telegram;

import sh.vork.orm.DatabaseEntity;

public record InputFormTokenRecord(
        String uuid,
        String sessionUuid,
        String eventId,
        String username,
        String requestCampaignUuid,
        String responderChannel,
        long expiresAt,
        boolean consumed,
        long createdAt,
        Long consumedAt
) implements DatabaseEntity {
}
