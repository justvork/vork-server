package sh.vork.ai.request;

import sh.vork.orm.DatabaseEntity;

import java.util.List;
import java.util.Map;

public record RequestInformationCampaign(
        String uuid,
        String sessionUuid,
        String eventId,
        String createdBy,
        String promptText,
        List<String> targetChannels,
        RequestResponsePolicy policy,
        int requiredResponses,
        List<String> respondedChannels,
        RequestCampaignStatus status,
        boolean resumeTriggered,
        long createdAt,
        long updatedAt,
                Long satisfiedAt,
                String parentSessionUuid,
                Map<String, String> childSessionUuidsByChannel,
                RequestResponseRouteMode responseRouteMode,
                boolean childSessionRoutingEnabled
) implements DatabaseEntity {

        public RequestInformationCampaign {
                if (parentSessionUuid == null || parentSessionUuid.isBlank()) {
                        parentSessionUuid = sessionUuid;
                }
                if (childSessionUuidsByChannel == null) {
                        childSessionUuidsByChannel = Map.of();
                } else {
                        childSessionUuidsByChannel = Map.copyOf(childSessionUuidsByChannel);
                }
                if (responseRouteMode == null) {
                        responseRouteMode = RequestResponseRouteMode.EXTERNAL_FORM;
                }
        }

        public RequestInformationCampaign(
                        String uuid,
                        String sessionUuid,
                        String eventId,
                        String createdBy,
                        String promptText,
                        List<String> targetChannels,
                        RequestResponsePolicy policy,
                        int requiredResponses,
                        List<String> respondedChannels,
                        RequestCampaignStatus status,
                        boolean resumeTriggered,
                        long createdAt,
                        long updatedAt,
                        Long satisfiedAt
        ) {
                this(uuid,
                                sessionUuid,
                                eventId,
                                createdBy,
                                promptText,
                                targetChannels,
                                policy,
                                requiredResponses,
                                respondedChannels,
                                status,
                                resumeTriggered,
                                createdAt,
                                updatedAt,
                                satisfiedAt,
                                sessionUuid,
                                Map.of(),
                                RequestResponseRouteMode.EXTERNAL_FORM,
                                false);
        }
}
