package sh.vork.ai.function;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * Input schema for the {@code encryptString} tool.
 */
public record EncryptStringRequest(
        @JsonProperty(required = true, value = "input")
        @JsonPropertyDescription("Plain text input to encrypt.")
        String input
) {}
