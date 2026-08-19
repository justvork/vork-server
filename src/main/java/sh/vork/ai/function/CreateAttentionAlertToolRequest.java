package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

public record CreateAttentionAlertToolRequest(
        @JsonPropertyDescription("Target channel names. Use usernames for user channels. Optional when selectedChannelName is provided.")
        List<String> channelNames,
        @JsonPropertyDescription("Single selected channel name returned by the channel selection form when disambiguation is required.")
        String selectedChannelName,
        @JsonPropertyDescription("Short alert title shown in the UI.")
        String alertName,
        @JsonPropertyDescription("Alert body text displayed to users.")
        String description,
        @JsonPropertyDescription("Resolution policy. Valid values: ACTION_REQUIRED or DISMISSABLE. Use DISMISSABLE unless there is an actionUrl to send the user to, then use ACTION_REQUIRED. Do not use FIRST_ACK or ALL_ACK.")
        String resolutionPolicy,
        @JsonPropertyDescription("Action URL for ACTION_REQUIRED alerts. If provided, resolutionPolicy should be ACTION_REQUIRED.")
        String actionUrl,
        @JsonPropertyDescription("Unix epoch milliseconds for when the alert should become due. If omitted, alert is due immediately.")
        Long attentionAt,
        @JsonPropertyDescription("Source type for provenance. Valid values include CUSTOM, MCP_STATUS_CHANGE, and SESSION_SUSPENSION.")
        String sourceType,
        @JsonPropertyDescription("Source identifier used to correlate and resolve alerts.")
        String sourceId
) {
}
