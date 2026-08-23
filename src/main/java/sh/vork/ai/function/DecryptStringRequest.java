package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code decryptString} tool.
 */
public record DecryptStringRequest(
        @JsonProperty(required = true, value = "input")
        @JsonPropertyDescription("Encrypted text input to decrypt.")
        String input
) {}
