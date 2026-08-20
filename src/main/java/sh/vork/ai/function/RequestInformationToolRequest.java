package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

public record RequestInformationToolRequest(
        @JsonPropertyDescription("Recipient channel names. Use usernames for user channels.")
        List<String> channelNames,
        @JsonProperty(required = true, value = "promptText")
        @JsonPropertyDescription("Required. Exact question to present in the responder's Information Required prompt. Keep this concise and specific (for example: 'When is the next catchup meeting with Acme Inc?').")
        String promptText,
        @JsonProperty(required = true, value = "requesterMessage")
        @JsonPropertyDescription("Required. Friendly first-person status message shown in the requestor's waiting view while responses are pending. Must be contextual and mention recipient names (for example: 'I am requesting the meeting time from Lee and will continue when Lee responds.').")
        String requesterMessage,
        @JsonProperty(required = true, value = "recipientMessage")
        @JsonPropertyDescription("Required. Message shown to recipients as the assistant intro bubble before the Information Required prompt. This should be a contextual greeting that references the requester and ask, and should not replace promptText.")
        String recipientMessage,
        @JsonPropertyDescription("Response policy: AUTO, FIRST, ALL, or QUORUM. AUTO lets the system infer policy from prompt text.")
        String responsePolicy,
        @JsonPropertyDescription("Required when responsePolicy is QUORUM. Number of distinct channel responses required before resuming.")
        Integer quorumCount,
        @JsonPropertyDescription("Whether to send out-of-band notifications in addition to attention alerts. Defaults to true.")
        Boolean sendNotifications,
        @JsonPropertyDescription("Optional custom alert title. Defaults to 'Information Requested'.")
        String alertName,
        @JsonPropertyDescription("Optional alert resolution policy for created request alerts: ACTION_REQUIRED or DISMISSABLE. Defaults to ACTION_REQUIRED.")
        String alertResolutionPolicy,
        @JsonPropertyDescription("Optional epoch-millis due time for the attention alert.")
        Long attentionAt,
        @JsonPropertyDescription("Internal resume field populated by the system when campaign threshold is satisfied. AI callers must not set this.")
        String requestCampaignId,
        @JsonPropertyDescription("Internal resume field containing aggregated participant responses as JSON. AI callers must not set this.")
        String responsesJson,
        @JsonPropertyDescription("Internal resume field with the number of aggregated participant responses. AI callers must not set this.")
        Integer responseCount
) {
}
