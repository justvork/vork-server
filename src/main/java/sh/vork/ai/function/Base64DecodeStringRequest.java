package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code base64DecodeString} tool.
 */
public record Base64DecodeStringRequest(
        @JsonProperty(required = true, value = "input")
        @JsonPropertyDescription("Base64-encoded text that decodes to a UTF-8 string.")
        String input
) {}
