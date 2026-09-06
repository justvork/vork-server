package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input for reading a specific chat history item from the active session.
 */
public record ReadChatHistoryRequest(
        @JsonProperty(value = "reference", required = true)
        @JsonPropertyDescription("History item reference. Supported formats: message UUID, 'uuid:<message-uuid>', zero-based index (e.g. '0', '5', 'index:5', '-1'), 'last', or 'last-assistant'.")
        String reference
) {}
