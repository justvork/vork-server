package sh.vork.ai.request;

import sh.vork.orm.DatabaseEntity;

import java.util.Map;

public record RequestInformationResponse(
        String uuid,
        String campaignUuid,
        String responderChannel,
        String action,
        Map<String, String> fields,
        long createdAt
) implements DatabaseEntity {
}
