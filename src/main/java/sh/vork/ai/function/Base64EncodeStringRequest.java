package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code base64EncodeString} tool.
 */
public record Base64EncodeStringRequest(
        @JsonProperty(required = true, value = "input")
        @JsonPropertyDescription("UTF-8 string content to encode as Base64.")
        String input
) {}
